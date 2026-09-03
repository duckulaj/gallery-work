package com.hawkins.gallery.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StopWatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hawkins.gallery.config.AppProperties;
import com.hawkins.gallery.domain.FaceDetection;
import com.hawkins.gallery.domain.KnownFaceExample;
import com.hawkins.gallery.domain.KnownPerson;
import com.hawkins.gallery.repository.AssetMetadataRepository;
import com.hawkins.gallery.repository.FaceDetectionRepository;
import com.hawkins.gallery.repository.KnownFaceExampleRepository;
import com.hawkins.gallery.repository.KnownPersonRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates face detection, recognition, and enrollment.
 *
 * <p>Detection/recognition flow (called during AI enrichment):
 * <ol>
 *   <li>POST image to the DeepFace microservice → list of {@link DeepFaceClient.DetectedFace}</li>
 *   <li>For each face, save a JPEG crop to {@code face-crops-dir}</li>
 *   <li>Compare the ArcFace embedding against {@code known_face_examples.embedding} via pgvector</li>
 *   <li>If cosine similarity exceeds the configured threshold, assign the matching person</li>
 *   <li>Persist each detection as a {@link FaceDetection} row</li>
 * </ol>
 *
 * <p>Enrollment flow (triggered when a user labels an unknown face):
 * <ol>
 *   <li>Find or create a {@link KnownPerson} for the given display name</li>
 *   <li>Create a {@link KnownFaceExample} linked to that person</li>
 *   <li>Copy the ArcFace embedding from {@code face_detections} into {@code known_face_examples}</li>
 *   <li>Mark the {@link FaceDetection} as identified</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaceDetectionService {

    /** Summary returned to the enrichment pipeline so it can update asset_metadata. */
    public record FaceDetectionSummary(int totalFaces, int matchedFaces, List<String> recognisedNames) {}

    private final DeepFaceClient deepFaceClient;
    private final FaceDetectionRepository detections;
    private final KnownFaceExampleRepository examples;
    private final KnownPersonRepository people;
    private final AssetMetadataRepository metas;
    private final EmbeddingService embed;
    private final KnownFaceService knownFaces;
    private final AppProperties props;
    private final ObjectMapper mapper;
    private final ConcurrentMap<String, ReentrantLock> assetLocks = new ConcurrentHashMap<>();

    // ── Detection / recognition ───────────────────────────────────────────────

    /**
     * Runs DeepFace detection on {@code imagePath}, persists the results, and returns
     * a summary of detected and recognised faces.
     *
     * <p>All errors are swallowed; an empty summary is returned so the caller's
     * enrichment pipeline is never interrupted by face-service failures.
     */
    @Transactional
    public FaceDetectionSummary detectAndRecognise(String assetId, Path imagePath) {
        ReentrantLock lock = assetLocks.computeIfAbsent(assetId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return detectAndRecogniseLocked(assetId, imagePath);
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads()) {
                assetLocks.remove(assetId, lock);
            }
        }
    }

    private FaceDetectionSummary detectAndRecogniseLocked(String assetId, Path imagePath) {
        if (!props.ai().faceRecognition().enabled()) {
            return new FaceDetectionSummary(0, 0, List.of());
        }

        StopWatch sw = new StopWatch("Face detection for asset " + assetId);

        sw.start("Delete stale detections");
        detections.deleteByAssetId(assetId);
        sw.stop();

        sw.start("DeepFace detection");
        List<DeepFaceClient.DetectedFace> faces = deepFaceClient.detect(imagePath);
        sw.stop();
        if (faces.isEmpty()) {
            log.info("{} | stages: {}", sw.shortSummary(), stageBreakdown(sw));
            return new FaceDetectionSummary(0, 0, List.of());
        }

        List<String> recognisedNames = new ArrayList<>();
        double threshold = props.ai().faceRecognition().threshold();

        sw.start("Match and persist faces");
        for (int i = 0; i < faces.size(); i++) {
            DeepFaceClient.DetectedFace face = faces.get(i);

            String cropPath = saveCrop(assetId, i, face.cropJpeg());
            String embeddingJson = embed.toJson(face.embedding());
            String vectorLit    = embed.toPgVectorLiteral(face.embedding());

            String personId   = null;
            String personName = null;
            Float  confidence = null;

            var nearest = detections.findNearestKnownFace(vectorLit);
            if (nearest.isPresent()) {
                var match = nearest.get();
                if (match.getSimilarity() != null) {
                    double similarity = match.getSimilarity();
                    if (similarity >= threshold) {
                        personId   = match.getPersonId();
                        personName = match.getDisplayName();
                        confidence = (float) similarity;
                        recognisedNames.add(personName);
                    } else {
                        log.debug("Nearest known face for asset {} was '{}' at {} (threshold {})",
                                assetId, match.getDisplayName(), similarity, threshold);
                    }
                }
            }

            FaceDetection fd = new FaceDetection();
            fd.setAssetId(assetId);
            fd.setBboxJson(mapper.valueToTree(face.bbox()).toString());
            fd.setEmbeddingJson(embeddingJson);
            fd.setPersonId(personId);
            fd.setPersonName(personName);
            fd.setConfidence(confidence);
            fd.setCropPath(cropPath);
            detections.save(fd);
        }
        sw.stop();

        log.info("{} | stages: {} | faces: {} | recognised: {}",
                sw.shortSummary(), stageBreakdown(sw), faces.size(), recognisedNames.size());
        return new FaceDetectionSummary(
            faces.size(), recognisedNames.size(), recognisedNames.stream().distinct().toList());
    }

    // ── Enrollment ────────────────────────────────────────────────────────────

    /**
     * Labels a previously detected (but unidentified) face with {@code displayName}.
     * Creates or reuses a {@link KnownPerson}, stores a new {@link KnownFaceExample}
     * with the ArcFace embedding, and marks the detection as identified.
     */
    @Transactional
    public void enrollFace(String detectionId, String displayName) {
        FaceDetection fd = detections.findById(detectionId)
                .orElseThrow(() -> new IllegalArgumentException("Detection not found: " + detectionId));

        String cleanName = displayName.trim();
        KnownPerson person = people.findByDisplayNameIgnoreCase(cleanName)
                .orElseGet(() -> people.save(new KnownPerson(cleanName)));

        KnownFaceExample example = new KnownFaceExample();
        example.setPerson(person);
        example.setSourceAssetId(fd.getAssetId());
        examples.saveAndFlush(example);

        // Copy the ArcFace embedding from face_detections → known_face_examples so
        // this person is immediately searchable via the pgvector HNSW index.
        int copied = detections.copyEmbeddingToExample(fd.getId(), example.getId());
        if (copied != 1) {
            throw new IllegalStateException("Could not store the face embedding for detection " + detectionId);
        }

        fd.setPersonId(person.getId());
        fd.setPersonName(person.getDisplayName());
        fd.setConfidence(1.0f);
        detections.save(fd);

        // Update asset_metadata.face_names to include the newly identified person.
        metas.findById(fd.getAssetId()).ifPresent(m -> {
            m.setFaceNames(knownFaces.mergeJsonList(m.getFaceNames(), cleanName));
            m.setAiUpdatedAt(Instant.now());
            metas.save(m);
        });

        log.info("Enrolled face detection {} as '{}'", detectionId, cleanName);
    }

    // ── Queries used by controllers / UI ─────────────────────────────────────

    public List<FaceDetection> getDetectionsForAsset(String assetId) {
        return detections.findByAssetIdOrderByCreatedAtAsc(assetId);
    }

    public java.util.Optional<FaceDetection> findDetection(String detectionId) {
        return detections.findById(detectionId);
    }

    /** Returns the set of asset IDs that have at least one unidentified face. */
    public Set<String> getUnidentifiedFaceAssetIds() {
        return Set.copyOf(detections.findAssetIdsWithUnidentifiedFaces());
    }

    public long countUnidentifiedFaces(String assetId) {
        return detections.countUnidentifiedByAssetId(assetId);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private String saveCrop(String assetId, int index, byte[] jpegBytes) {
        if (jpegBytes == null || jpegBytes.length == 0) {
            return null;
        }
        try {
            Path dir = props.ai().faceRecognition().faceCropsDir().toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path file = dir.resolve(assetId + "-" + index + ".jpg");
            Files.write(file, jpegBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return file.toString();
        } catch (IOException ex) {
            log.warn("Could not save face crop for asset {}: {}", assetId, ex.getMessage());
            return null;
        }
    }

    private String stageBreakdown(StopWatch sw) {
        return java.util.Arrays.stream(sw.getTaskInfo())
                .map(info -> info.getTaskName() + "=" + info.getTimeMillis() + "ms")
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
