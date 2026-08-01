package com.hawkins.gallery.service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PostConstruct;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hawkins.gallery.domain.AiStatus;
import com.hawkins.gallery.domain.Asset;
import com.hawkins.gallery.domain.AssetEmbedding;
import com.hawkins.gallery.domain.AssetMetadata;
import com.hawkins.gallery.repository.AssetEmbeddingRepository;
import com.hawkins.gallery.repository.AssetMetadataRepository;
import com.hawkins.gallery.repository.AssetRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiEnrichmentService {
    private final AssetRepository assets;
    private final AssetMetadataRepository metas;
    private final AssetEmbeddingRepository embeddings;
    private final ImageService images;
    private final AiTaggingService ai;
    private final EmbeddingService embed;
    private final KnownFaceService knownFaces;
    private final FaceDetectionService faceDetection;
    private final NsfwClient nsfw;
    private final PlatformTransactionManager transactionManager;
    private final ExecutorService enrichmentExecutor;
    private final ObjectMapper mapper;
    private final java.util.concurrent.ConcurrentMap<String, CompletableFuture<Void>> inFlight = new java.util.concurrent.ConcurrentHashMap<>();
    private TransactionTemplate txTemplate;

    @Value("${app.ai.background.enabled:true}")
    private boolean enabled;

    @Value("${app.ai.background.batch-size:2}")
    private int batchSize;

    /**
     * Guards the background scheduler. Starts {@code false} on every JVM start so
     * the scheduler is idle until the user (or the night scheduler) explicitly
     * queues work. This avoids a mass-UPDATE on the asset_metadata table at startup
     * which would create index bloat and stale planner statistics.
     */
    private volatile boolean queueActive = false;

    /** Called by controllers and the night scheduler when they queue work. */
    public void activateQueue() {
        queueActive = true;
    }

    /** Called by the halt endpoint and the night scheduler's end window. */
    public void deactivateQueue() {
        queueActive = false;
    }

    @PostConstruct
    void resetInterruptedJobsOnStartup() {
        // Only reset rows that were mid-flight when the JVM was killed (PROCESSING → FAILED).
        // PENDING/RETRY rows are left intact; they will be processed once the queue
        // is explicitly activated (activateQueue). This avoids a mass-UPDATE that
        // would bloat the idx_asset_metadata_ai_queue index and cause expensive
        // post-restart scans.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            int failed = metas.resetInterruptedProcessing();
            if (failed > 0) {
                log.info("Startup: reset {} interrupted PROCESSING job(s) to FAILED", failed);
            }
        });
    }

    @Scheduled(fixedDelayString = "${app.ai.background.fixed-delay-ms:5000}")
    public void processQueue() {
        if (!enabled || !queueActive) {
            return;
        }

        var ids = metas.findNextAiQueueBatch(Math.max(1, batchSize));
        if (ids.isEmpty()) {
            return;
        }

        // Phase 1 — Vision/AI analysis (parallel, all items).
        // All vision calls are batched together so Ollama processes them with
        // the vision model fully loaded, then unloads it once for the entire batch.
        List<CompletableFuture<EnrichmentIntermediate>> visionFutures = ids.stream()
                .map(id -> {
                    CompletableFuture<EnrichmentIntermediate> f =
                            CompletableFuture.supplyAsync(() -> claimAndAnalyse(id), enrichmentExecutor);
                    // Track in inFlight so cancellation can reach this item
                    inFlight.put(id, f.thenAccept(r -> {}));
                    return f;
                })
                .toList();

        CompletableFuture.allOf(visionFutures.toArray(new CompletableFuture[0])).join();

        List<EnrichmentIntermediate> intermediates = visionFutures.stream()
                .map(f -> f.getNow(null))
                .filter(Objects::nonNull)
                .toList();

        if (intermediates.isEmpty()) {
            ids.forEach(inFlight::remove);
            return;
        }

        // Phase 2a — Batch embedding (single model call for the entire batch).
        // Running this sequentially before Phase 2b ensures the embedding model is loaded
        // once for all items, eliminating repeated vision↔embedding model-swap overhead.
        List<String> embeddingTexts = intermediates.stream()
                .map(ir -> buildEmbeddingText(ir.snapshot(), ir.aiAnalysis(), ir.original()))
                .toList();
        List<float[]> vectors;
        long embedStart = System.currentTimeMillis();
        try {
            vectors = embed.embedBatch(embeddingTexts);
        } catch (Exception ex) {
            log.error("Batch embedding failed for {} asset(s): {}", intermediates.size(), ex.getMessage());
            intermediates.forEach(ir -> markFailed(ir.assetId(), ex));
            ids.forEach(inFlight::remove);
            return;
        }
        long totalEmbedMs = System.currentTimeMillis() - embedStart;
        long embedMsPerAsset = intermediates.isEmpty() ? 0 : totalEmbedMs / intermediates.size();

        // Phase 2b — Persist (parallel, with pre-computed vectors).
        List<CompletableFuture<Void>> saveFutures = new java.util.ArrayList<>();
        for (int i = 0; i < intermediates.size(); i++) {
            final EnrichmentIntermediate ir = intermediates.get(i);
            final float[] vector = vectors.get(i);
            CompletableFuture<Void> f = CompletableFuture.runAsync(
                    () -> persistAsset(ir, vector, embedMsPerAsset), enrichmentExecutor);
            f.whenComplete((r, t) -> inFlight.remove(ir.assetId()));
            saveFutures.add(f);
        }
        CompletableFuture.allOf(saveFutures.toArray(new CompletableFuture[0])).join();
    }

    /**
     * Cancel all in-flight enrichment tasks. Attempts to cancel running futures.
     * Returns the number of tasks that were requested to cancel.
     */
    public int cancelAllInFlight() {
        String[] keys = inFlight.keySet().toArray(new String[0]);
        int cancelled = 0;
        for (String id : keys) {
            CompletableFuture<Void> f = inFlight.get(id);
            if (f != null) {
                boolean c = f.cancel(true);
                if (c) cancelled++;
            }
        }
        log.info("Requested cancellation of {} in-flight AI enrichment tasks", cancelled);
        return cancelled;
    }

    public AiQueueStats stats() {
        long pending = metas.countByAiStatus(AiStatus.PENDING.name()) + metas.countByAiStatus(AiStatus.RETRY.name());
        long processing = metas.countByAiStatus(AiStatus.PROCESSING.name());
        long complete = metas.countByAiStatus(AiStatus.COMPLETE.name());
        long failed = metas.countByAiStatus(AiStatus.FAILED.name());
        long cancelled = metas.countByAiStatus(AiStatus.CANCELLED.name());
        return new AiQueueStats(pending, processing, complete, failed, cancelled);
    }

    /** Phase 1: claim the asset and run EXIF + vision + face matching. Returns null if skipped/failed. */
    private EnrichmentIntermediate claimAndAnalyse(String assetId) {
        if (!claim(assetId)) {
            return null;
        }
        try {
            AssetSnapshot snapshot = loadSnapshot(assetId);
            Path original = Path.of(snapshot.storagePath()).toAbsolutePath().normalize();

            org.springframework.util.StopWatch sw =
                    new org.springframework.util.StopWatch("Enrichment for " + snapshot.filename());

            sw.start("EXIF extraction");
            Map<String, String> exif = images.exif(original);
            sw.stop();

            sw.start("AI tagging/vision");
            AiTaggingService.AiImageAnalysis aiAnalysis = ai.analyzeImage(original, snapshot.filename(), exif);
            sw.stop();

            sw.start("Face detection");
            FaceDetectionService.FaceDetectionSummary faceResult =
                    faceDetection.detectAndRecognise(assetId, original);
            sw.stop();

            sw.start("NSFW detection");
            NsfwClient.Result nsfwResult = nsfw.detect(original);
            sw.stop();

            return new EnrichmentIntermediate(assetId, snapshot, original, exif, aiAnalysis, faceResult, nsfwResult, sw);
        } catch (Exception ex) {
            markFailed(assetId, ex);
            return null;
        }
    }

    /** Phase 2b: persist pre-computed embedding and AI analysis to the DB. */
    private void persistAsset(EnrichmentIntermediate ir, float[] vector, long embedMs) {
        try {
            if (Thread.currentThread().isInterrupted()) {
                throw new RuntimeException("Enrichment interrupted");
            }
            AssetMetadata maybe = metas.findById(ir.assetId()).orElse(null);
            if (maybe != null && AiStatus.CANCELLED.name().equals(maybe.getAiStatus())) {
                log.info("Enrichment for asset {} aborted because metadata marked CANCELLED", ir.assetId());
                return;
            }

            log.info("{} | embed: {}ms (batched)", ir.sw().shortSummary(), embedMs);

            tx().executeWithoutResult(status -> {
                Asset asset = assets.findById(ir.assetId()).orElseThrow();
                AssetMetadata m = metas.findById(ir.assetId()).orElseGet(AssetMetadata::new);
                if (AiStatus.CANCELLED.name().equals(m.getAiStatus())) {
                    log.info("Not marking asset {} COMPLETE because status is already CANCELLED", ir.assetId());
                    return;
                }
                m.setAsset(asset);
                m.setAiCaption(ir.aiAnalysis().caption());
                m.setAiTags(ir.aiAnalysis().tags());
                m.setDominantColors(ir.aiAnalysis().dominantColors());
                m.setFaceCount(ir.aiAnalysis().faceCount());
                // If DeepFace detected faces, its recognised names take precedence;
                // otherwise fall back to the vision model's face_names output.
                List<String> deepFaceNames = ir.faceResult() != null
                        ? ir.faceResult().recognisedNames() : List.of();
                if (ir.faceResult() != null && ir.faceResult().totalFaces() > 0) {
                    m.setFaceCount(ir.faceResult().totalFaces());
                }
                // Preserve manually tagged face names by merging existing DB names with AI-detected ones.
                // Without this, a full re-index would overwrite user-applied face tags.
                m.setFaceNames(knownFaces.mergeRecognisedNames(
                        m.getFaceNames(),
                        deepFaceNames.isEmpty()
                                ? knownFaces.readJsonList(ir.aiAnalysis().faceNames())
                                : deepFaceNames,
                        knownFaces.readJsonList(ir.aiAnalysis().faceDescriptions())));
                m.setFaceDescriptions(ir.aiAnalysis().faceDescriptions());
                m.setSceneType(ir.aiAnalysis().sceneType());
                m.setSceneLabels(ir.aiAnalysis().sceneLabels());
                m.setNsfwScore(ir.nsfwResult().score());
                m.setNsfwLevel(ir.nsfwResult().level());
                m.setNsfwLabels(toJson(ir.nsfwResult().labels()));
                if (m.getNsfwReviewStatus() == null || m.getNsfwReviewStatus().isBlank()) {
                    m.setNsfwReviewStatus("UNREVIEWED");
                }
                m.setExifJson(toJson(ir.exif()));
                m.setAiModel(ir.aiAnalysis().model());
                m.setAiStatus(AiStatus.COMPLETE.name());
                m.setAiError(null);
                m.setAiUpdatedAt(Instant.now());
                for (org.springframework.util.StopWatch.TaskInfo info : ir.sw().getTaskInfo()) {
                    switch (info.getTaskName()) {
                        case "EXIF extraction" -> m.setTimingExifMs(info.getTimeMillis());
                        case "AI tagging/vision" -> m.setTimingAiMs(info.getTimeMillis());
                        case "Face detection" -> m.setTimingFaceMs(info.getTimeMillis());
                        case "NSFW detection" -> m.setTimingNsfwMs(info.getTimeMillis());
                    }
                }
                m.setTimingEmbedMs(embedMs);
                m.setTimingTotalMs(ir.sw().getTotalTimeMillis() + embedMs);
                metas.save(m);

                String model = "ollama:mxbai-embed-large";
                String vectorJson = embed.toJson(vector);
                try {
                    embeddings.upsertVector(
                            ir.assetId(),
                            model,
                            vector.length,
                            embed.toPgVectorLiteral(vector),
                            vectorJson);
                } catch (Exception nativeVectorEx) {
                    // Compatibility fallback for the pre-pgvector/MySQL schema.
                    // Remove this branch once the PostgreSQL migration is complete.
                    log.warn("Native vector upsert failed for asset {}; falling back to embedding_json only: {}",
                            ir.assetId(), nativeVectorEx.getMessage());
                    AssetEmbedding e = embeddings.findById(ir.assetId()).orElseGet(AssetEmbedding::new);
                    e.setAsset(asset);
                    e.setModel(model);
                    e.setDimensions(vector.length);
                    e.setEmbeddingJson(vectorJson);
                    embeddings.save(e);
                }
            });
        } catch (Exception ex) {
            markFailed(ir.assetId(), ex);
        }
    }

    private record EnrichmentIntermediate(
            String assetId,
            AssetSnapshot snapshot,
            Path original,
            Map<String, String> exif,
            AiTaggingService.AiImageAnalysis aiAnalysis,
            FaceDetectionService.FaceDetectionSummary faceResult,
            NsfwClient.Result nsfwResult,
            org.springframework.util.StopWatch sw) {}

    private boolean claim(String assetId) {
        Boolean claimed = tx().execute(status -> {
            AssetMetadata m = metas.findById(assetId).orElse(null);
            if (m == null) {
                return false;
            }
            String current = m.getAiStatus();
            if (AiStatus.COMPLETE.name().equals(current)) {
                return false;
            }
            m.setAiStatus(AiStatus.PROCESSING.name());
            m.setAiError(null);
            m.setAiUpdatedAt(Instant.now());
            metas.save(m);
            return true;
        });
        return Boolean.TRUE.equals(claimed);
    }

    private AssetSnapshot loadSnapshot(String assetId) {
        return tx().execute(status -> {
            Asset asset = assets.findById(assetId).orElseThrow();
            return new AssetSnapshot(asset.getId(), asset.getFilename(), asset.getStoragePath());
        });
    }

    private void markFailed(String assetId, Exception ex) {
        tx().executeWithoutResult(status -> {
            AssetMetadata m = metas.findById(assetId).orElse(null);
            if (m == null) {
                return;
            }
            // If the metadata has already been set to CANCELLED due to a user cancellation,
            // don't overwrite the cancellation message with the exception text.
            if (AiStatus.CANCELLED.name().equals(m.getAiStatus()) && "Cancelled by user".equals(m.getAiError())) {
                return;
            }
            m.setAiStatus(AiStatus.FAILED.name());
            m.setAiError(trim(ex.getMessage(), 1800));
            m.setAiUpdatedAt(Instant.now());
            metas.save(m);
        });
    }

    private TransactionTemplate tx() {
        if (txTemplate == null) {
            txTemplate = new TransactionTemplate(transactionManager);
            txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
        return txTemplate;
    }

    private String buildEmbeddingText(AssetSnapshot asset, AiTaggingService.AiImageAnalysis analysis, Path original) {
        return String.join("\n",
                safe(asset.filename()),
                safe(asset.storagePath()),
                original == null ? "" : safe(original.toAbsolutePath().normalize().toString()),
                safe(analysis.caption()),
                safe(analysis.tags()),
                safe(analysis.dominantColors()),
                safe(analysis.faceNames()),
                safe(analysis.faceDescriptions()),
                safe(analysis.sceneType()),
                safe(analysis.sceneLabels()));
    }

    private String toJson(Object values) {
        try {
            return mapper.writeValueAsString(values);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String trim(String value, int max) {
        if (value == null) {
            return "Unknown AI enrichment failure";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record AssetSnapshot(String id, String filename, String storagePath) {
    }

    public record AiQueueStats(long pending, long processing, long complete, long failed, long cancelled) {
        public String summary() {
            return "AI queue: " + pending + " pending, " + processing + " processing, " + complete + " complete, " + failed + " failed, " + cancelled + " cancelled.";
        }
    }
}
