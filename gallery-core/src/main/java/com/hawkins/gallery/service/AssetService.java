package com.hawkins.gallery.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hawkins.gallery.domain.AiStatus;
import com.hawkins.gallery.domain.Asset;
import com.hawkins.gallery.domain.AssetMetadata;
import com.hawkins.gallery.domain.Folder;
import com.hawkins.gallery.event.AssetIndexedEvent;
import com.hawkins.gallery.repository.AssetEmbeddingRepository;
import com.hawkins.gallery.repository.AssetMetadataRepository;
import com.hawkins.gallery.repository.AssetRepository;
import com.hawkins.gallery.repository.FolderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssetService {
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "tif", "tiff");

    /** System-level directories that must never be indexed. */
    private static final Set<Path> BLOCKED_ROOTS = Set.of(
            Path.of("/etc"), Path.of("/proc"), Path.of("/sys"),
            Path.of("/dev"), Path.of("/run"), Path.of("/boot"));

    private final FolderRepository folders;
    private final AssetRepository assets;
    private final AssetMetadataRepository metas;
    private final AssetEmbeddingRepository embeddings;
    private final ImageService images;
    private final PlatformTransactionManager transactionManager;
    private final AlbumService albumService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper mapper = new ObjectMapper();
    private TransactionTemplate requiresNewTx;

    @jakarta.annotation.PostConstruct
    private void init() {
        requiresNewTx = new TransactionTemplate(transactionManager);
        requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public Folder root() {
        return folders.findByNameAndParentId("Gallery", null)
                .orElseGet(() -> folders.save(new Folder("Gallery", null)));
    }


    public DirectoryIndexResult indexDirectory(String folderId, String directoryPath, boolean recursive) {
        Path root = Path.of(directoryPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Directory does not exist: " + root);
        }
        // Prevent indexing sensitive system directories
        for (Path blocked : BLOCKED_ROOTS) {
            if (root.startsWith(blocked)) {
                throw new IllegalArgumentException("Indexing system directory is not allowed: " + root);
            }
        }

        Folder rootAlbum = albumService.resolveRootAlbum(root);
        int maxDepth = recursive ? Integer.MAX_VALUE : 1;
        int indexed = 0;
        int skipped = 0;
        int failed = 0;

        try {
            Set<Path> foldersToSkip = foldersToSkip(root, maxDepth, recursive);
            Map<String, Folder> albumByDir = resolveAlbums(root, rootAlbum, maxDepth, foldersToSkip, recursive);

            List<Path> candidates;
            try (Stream<Path> stream = Files.walk(root, maxDepth)) {
                candidates = stream
                        .filter(Files::isRegularFile)
                        .filter(this::looksLikeImage)
                        .filter(p -> !isUnderFolderToSkip(p, foldersToSkip))
                        .sorted()
                        .toList();
            }

            for (Path image : candidates) {
                try {
                    String checksum = images.sha256(image);
                    Path normalized = image.toAbsolutePath().normalize();
                    String targetFolderId = albumByDir.getOrDefault(
                            normalized.getParent().toAbsolutePath().normalize().toString(), rootAlbum).getId();
                    if (assets.existsByFolderIdAndChecksum(targetFolderId, checksum)
                            || assets.existsByFolderIdAndStoragePath(targetFolderId, normalized.toString())) {
                        skipped++;
                        continue;
                    }
                    newAssetTransaction().executeWithoutResult(status ->
                            createAssetInternal(targetFolderId, normalized, image.getFileName().toString(), contentType(image)));
                    indexed++;
                } catch (DataIntegrityViolationException ex) {
                    // Concurrent indexing already inserted this asset; treat as skipped
                    log.debug("Skipping duplicate asset {} (concurrent insert)", image);
                    skipped++;
                } catch (RuntimeException ex) {
                    log.warn("Failed to index {}", image, ex);
                    failed++;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Directory indexing failed", e);
        }

        return new DirectoryIndexResult(root.toString(), indexed, skipped, failed, true, rootAlbum.getId());
    }

    public DirectoryIndexResult reindexAi(String folderId) {
        return queueAiForFolder(folderId, true);
    }

    public DirectoryIndexResult queueMissingAi(String folderId) {
        return queueAiForFolder(folderId, false);
    }

    /**
     * Queues assets that are missing AI metadata across all folders.
     * Used by the night scheduler to pick up newly indexed images.
     *
     * @return total number of assets queued
     */
    public int queueMissingAiGlobal() {
        int total = 0;
        for (var folder : folders.findAll()) {
            try {
                total += queueAiForFolder(folder.getId(), false).indexed();
            } catch (Exception e) {
                log.warn("Night scheduler: failed to queue missing AI for folder {}: {}", folder.getId(), e.getMessage());
            }
        }
        return total;
    }

    /**
     * Force re-queues all assets across all folders so the latest known-face
     * labels are applied by background AI processing.
     * Used by the night scheduler alongside {@link #queueMissingAiGlobal()}.
     *
     * @return total number of assets queued
     */
    public int applyKnownFacesGlobal() {
        int total = 0;
        for (var folder : folders.findAll()) {
            try {
                total += queueAiForFolder(folder.getId(), true).indexed();
            } catch (Exception e) {
                log.warn("Night scheduler: failed to apply known faces for folder {}: {}", folder.getId(), e.getMessage());
            }
        }
        return total;
    }

    @Transactional
    public int haltAiProcessing() {
        // Find all metadata rows that are pending, retry or currently processing
        var statuses = java.util.List.of(AiStatus.PENDING.name(), AiStatus.RETRY.name(), AiStatus.PROCESSING.name());
        var list = metas.findByAiStatusIn(statuses);
        int changed = 0;
        Instant now = Instant.now();
        for (var m : list) {
            String current = m.getAiStatus();
            if (AiStatus.PENDING.name().equals(current) || AiStatus.RETRY.name().equals(current) || AiStatus.PROCESSING.name().equals(current)) {
                m.setAiStatus(AiStatus.CANCELLED.name());
                m.setAiError("Cancelled by user");
                m.setAiUpdatedAt(now);
                metas.save(m);
                changed++;
            }
        }
        log.info("Halted {} AI jobs (pending/processing)", changed);
        return changed;
    }

    private DirectoryIndexResult queueAiForFolder(String folderId, boolean force) {
        List<Asset> candidates = assets.findByFolderIdOrderByCreatedAtDesc(folderId);
        int queued = 0;
        int skipped = 0;
        int failed = 0;

        for (Asset asset : candidates) {
            try {
                boolean changed = Boolean.TRUE.equals(newAssetTransaction().execute(status -> queueAssetAiInternal(asset.getId(), force)));
                if (changed) {
                    queued++;
                } else {
                    skipped++;
                }
            } catch (TransactionException ex) {
                failed++;
            }
        }

        return new DirectoryIndexResult(force ? "existing folder assets" : "assets missing AI metadata", queued, skipped, failed, true, null);
    }

    private boolean queueAssetAiInternal(String assetId, boolean force) {
        Asset asset = assets.findById(assetId).orElseThrow();
        AssetMetadata m = metas.findById(assetId).orElseGet(AssetMetadata::new);
        m.setAsset(asset);

        String currentStatus = m.getAiStatus();
        boolean isIncomplete = AiStatus.CANCELLED.name().equals(currentStatus) || AiStatus.FAILED.name().equals(currentStatus);
        if (!force && !isIncomplete && hasAiMetadata(m)) {
            return false;
        }

        m.setAiStatus(AiStatus.PENDING.name());
        m.setAiError(null);
        m.setAiUpdatedAt(Instant.now());
        metas.save(m);
        return true;
    }

    private boolean hasAiMetadata(AssetMetadata m) {
        return notBlank(m.getAiCaption())
                || notBlank(m.getAiTags())
                || notBlank(m.getDominantColors())
                || notBlank(m.getFaceNames())
                || notBlank(m.getSceneType())
                || notBlank(m.getSceneLabels());
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank() && !"[]".equals(value.trim());
    }

    private TransactionTemplate newAssetTransaction() {
        return requiresNewTx;
    }

    private Asset createAssetInternal(String folderId, Path original, String filename, String contentType) {
        try {
            images.ensureDirs();
            Folder folder = folders.findById(folderId).orElseThrow();
            Asset a = new Asset();
            a.setFolder(folder);
            a.setFilename(filename);
            a.setContentType(contentType);
            a.setSizeBytes(Files.size(original));
            a.setStoragePath(original.toString());
            a.setChecksum(images.sha256(original));
            int[] wh = images.dimensions(original);
            a.setWidth(wh[0]);
            a.setHeight(wh[1]);
            if (a.getContentType().startsWith("image/")) {
                Path thumb = images.thumbnail(original, a.getId());
                a.setThumbnailPath(thumb.toString());
            }
            Asset saved = assets.save(a);

            Map<String, String> exif = images.exif(original);
            AssetMetadata m = new AssetMetadata();
            m.setAsset(saved);
            m.setExifJson(mapper.writeValueAsString(exif));
            // AI status is intentionally left null — enrichment is only queued
            // when the user explicitly requests it or the night scheduler runs.
            metas.save(m);
            eventPublisher.publishEvent(new AssetIndexedEvent(saved.getId()));

            return saved;
        } catch (IOException e) {
            throw new IllegalStateException("Asset indexing failed for " + original, e);
        }
    }


    private Set<Path> foldersToSkip(Path root, int maxDepth, boolean recursive) throws Exception {
        Set<Path> foldersToSkip = new HashSet<>();
        if (!recursive) {
            return foldersToSkip;
        }
        try (Stream<Path> dirs = Files.walk(root, maxDepth)) {
            dirs.filter(Files::isDirectory)
                    .filter(d -> !d.toAbsolutePath().normalize().equals(root))
                    .filter(d -> d.getFileName() != null
                            && (d.getFileName().toString().startsWith(".")
                            || d.getFileName().toString().startsWith("_")))
                    .forEach(hiddenDir -> {
                        try (Stream<Path> children = Files.list(hiddenDir)) {
                            if (children.filter(Files::isRegularFile).noneMatch(this::looksLikeImage)) {
                                foldersToSkip.add(hiddenDir.toAbsolutePath().normalize());
                            }
                        } catch (Exception e) {
                            foldersToSkip.add(hiddenDir.toAbsolutePath().normalize());
                        }
                    });
        }
        return foldersToSkip;
    }

    private Map<String, Folder> resolveAlbums(Path root, Folder rootAlbum, int maxDepth, Set<Path> foldersToSkip,
            boolean recursive) throws Exception {
        Map<String, Folder> albumByDir = new HashMap<>();
        albumByDir.put(root.toString(), rootAlbum);
        if (!recursive) {
            return albumByDir;
        }
        try (Stream<Path> dirs = Files.walk(root, maxDepth).filter(Files::isDirectory)) {
            dirs.sorted()
                    .filter(dir -> !isUnderFolderToSkip(dir, foldersToSkip))
                    .forEach(dir -> {
                        Path norm = dir.toAbsolutePath().normalize();
                        if (!albumByDir.containsKey(norm.toString())) {
                            albumByDir.put(norm.toString(), albumService.resolveAlbumForDir(norm, root, rootAlbum));
                        }
                    });
        }
        return albumByDir;
    }

    private boolean isUnderFolderToSkip(Path path, Set<Path> foldersToSkip) {
        if (foldersToSkip.isEmpty()) return false;
        Path p = path.toAbsolutePath().normalize();
        for (Path skipped : foldersToSkip) {
            if (p.startsWith(skipped)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeImage(Path path) {
        String filename = path.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return false;
        }
        return IMAGE_EXTENSIONS.contains(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private String contentType(Path path) {
        try {
            String detected = Files.probeContentType(path);
            if (detected != null && detected.startsWith("image/")) {
                return detected;
            }
        } catch (IOException ignored) {
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".bmp")) return "image/bmp";
        if (name.endsWith(".tif") || name.endsWith(".tiff")) return "image/tiff";
        return "image/jpeg";
    }

    @Transactional
    public void delete(List<String> ids) {
        for (String id : ids) {
            assets.findById(id).ifPresent(asset -> {
                if (asset.getThumbnailPath() != null) {
                    try {
                        Files.deleteIfExists(Path.of(asset.getThumbnailPath()));
                    } catch (IOException e) {
                        log.warn("Failed to delete thumbnail {}: {}", asset.getThumbnailPath(), e.getMessage());
                    }
                }
            });
        }
        embeddings.deleteAllById(ids);
        metas.deleteAllById(ids);
        assets.deleteAllById(ids);
    }

    @Transactional
    public void deleteByFolderId(String folderId) {
        List<Asset> folderAssets = assets.findByFolderId(folderId);
        if (!folderAssets.isEmpty()) {
            delete(folderAssets.stream().map(Asset::getId).toList());
        }
    }

    public Optional<Asset> find(String id) {
        return assets.findById(id);
    }

    public record DirectoryIndexResult(String directory, int indexed, int skipped, int failed, boolean aiQueued, String rootAlbumId) {
        public String message() {
            if ("existing folder assets".equals(directory)) {
                return "Queued " + indexed + " image" + (indexed == 1 ? "" : "s")
                        + " for background AI re-indexing"
                        + (skipped > 0 ? "; " + skipped + " already skipped" : "")
                        + (failed > 0 ? "; " + failed + " failed." : ".");
            }
            if ("assets missing AI metadata".equals(directory)) {
                return "Queued " + indexed + " image" + (indexed == 1 ? "" : "s")
                        + " missing AI metadata for background processing"
                        + (skipped > 0 ? "; " + skipped + " already had AI metadata" : "")
                        + (failed > 0 ? "; " + failed + " failed." : ".");
            }
            return "Indexed " + indexed + " image" + (indexed == 1 ? "" : "s")
                    + " from " + directory
                    + ". Skipped " + skipped + " duplicate" + (skipped == 1 ? "" : "s")
                    + (failed > 0 ? "; " + failed + " failed" : "")
                    + ". AI enrichment is queued in the background.";
        }
    }
}
