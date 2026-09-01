package com.hawkins.gallery.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hawkins.gallery.domain.AiStatus;
import com.hawkins.gallery.domain.Asset;
import com.hawkins.gallery.domain.AssetMetadata;
import com.hawkins.gallery.domain.Folder;
import com.hawkins.gallery.event.AssetIndexedEvent;
import com.hawkins.gallery.config.AppProperties;
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
@SuppressWarnings("null")
public class AssetService {
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "tif", "tiff");

    private final FolderRepository folders;
    private final AssetRepository assets;
    private final AssetMetadataRepository metas;
    private final AssetEmbeddingRepository embeddings;
    private final ImageService images;
    private final AppProperties properties;
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
        Path root;
        try {
            root = Path.of(directoryPath).toRealPath();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Directory does not exist", ex);
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Directory does not exist: " + root);
        }
        boolean allowed = properties.importRoots().stream().map(this::realRoot).anyMatch(root::startsWith);
        if (!allowed) {
            throw new IllegalArgumentException("Directory is outside the configured import roots");
        }

        Folder rootAlbum = albumService.resolveRootAlbum(root);
        int maxDepth = recursive ? Integer.MAX_VALUE : 1;
        int indexed = 0;
        int skipped = 0;
        int failed = 0;

        try {
            TreeScan tree = scanTree(root, maxDepth);
            Set<Path> foldersToSkip = foldersToSkip(root, tree.directories(), tree.images(), recursive);
            Map<String, Folder> albumByDir = resolveAlbums(root, rootAlbum, tree.directories(), foldersToSkip, recursive);
            Set<AssetIdentity> knownAssets = new HashSet<>();
            assets.findByFolderIdIn(albumByDir.values().stream().map(Folder::getId).collect(java.util.stream.Collectors.toSet()))
                    .forEach(asset -> {
                        knownAssets.add(new AssetIdentity(asset.getFolder().getId(), asset.getChecksum(), null));
                        knownAssets.add(new AssetIdentity(asset.getFolder().getId(), null, asset.getStoragePath()));
                    });
            List<Path> candidates = tree.images().stream()
                        .filter(p -> !isUnderFolderToSkip(p, foldersToSkip))
                        .sorted()
                        .toList();

            for (Path image : candidates) {
                try {
                    String checksum = images.sha256(image);
                    Path normalized = image.toAbsolutePath().normalize();
                    String targetFolderId = albumByDir.getOrDefault(
                            normalized.getParent().toAbsolutePath().normalize().toString(), rootAlbum).getId();
                    if (knownAssets.contains(new AssetIdentity(targetFolderId, checksum, null))
                            || knownAssets.contains(new AssetIdentity(targetFolderId, null, normalized.toString()))) {
                        skipped++;
                        continue;
                    }
                    newAssetTransaction().executeWithoutResult(status ->
                            createAssetInternal(targetFolderId, normalized, image.getFileName().toString(),
                                    contentType(image), checksum));
                    knownAssets.add(new AssetIdentity(targetFolderId, checksum, null));
                    knownAssets.add(new AssetIdentity(targetFolderId, null, normalized.toString()));
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

    private Path realRoot(Path configured) {
        try {
            return configured.toRealPath();
        } catch (IOException ex) {
            return configured.toAbsolutePath().normalize();
        }
    }

    private TreeScan scanTree(Path root, int maxDepth) throws IOException {
        List<Path> directories = new java.util.ArrayList<>();
        List<Path> imagesFound = new java.util.ArrayList<>();
        Files.walkFileTree(root, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), maxDepth,
                new SimpleFileVisitor<>() {
                    @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        directories.add(dir);
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile() && looksLikeImage(file)) imagesFound.add(file);
                        return FileVisitResult.CONTINUE;
                    }
                });
        return new TreeScan(directories, imagesFound);
    }

    @Transactional
    public DirectoryIndexResult reindexAi(String folderId) {
        return queueAiForFolder(folderId, true);
    }

    @Transactional
    public DirectoryIndexResult queueMissingAi(String folderId) {
        return queueAiForFolder(folderId, false);
    }

    /**
     * Queues assets that are missing AI metadata across all folders.
     * Used by the night scheduler to pick up newly indexed images.
     *
     * @return total number of assets queued
     */
    @Transactional
    public int queueMissingAiGlobal() {
        return metas.queueMissingGlobal();
    }

    /**
     * Force re-queues all assets across all folders so the latest known-face
     * labels are applied by background AI processing.
     * Used by the night scheduler alongside {@link #queueMissingAiGlobal()}.
     *
     * @return total number of assets queued
     */
    @Transactional
    public int applyKnownFacesGlobal() {
        return metas.queueAllGlobal();
    }

    @Transactional
    public int haltAiProcessing() {
        int changed = metas.cancelActive();
        log.info("Halted {} AI jobs (pending/processing)", changed);
        return changed;
    }

    private DirectoryIndexResult queueAiForFolder(String folderId, boolean force) {
        long total = assets.countByFolderId(folderId);
        int queued = metas.queueFolder(folderId, force);
        int skipped = (int) Math.max(0, total - queued);
        return new DirectoryIndexResult(force ? "existing folder assets" : "assets missing AI metadata",
                queued, skipped, 0, true, null);
    }

    private TransactionTemplate newAssetTransaction() {
        return requiresNewTx;
    }

    private Asset createAssetInternal(String folderId, Path original, String filename, String contentType,
            String checksum) {
        try {
            images.ensureDirs();
            Folder folder = folders.findById(folderId).orElseThrow();
            Asset a = new Asset();
            a.setFolder(folder);
            a.setFilename(filename);
            a.setContentType(contentType);
            a.setSizeBytes(Files.size(original));
            a.setStoragePath(original.toString());
            a.setChecksum(checksum);
            if (a.getContentType().startsWith("image/")) {
                ImageService.PreparedImage prepared = images.prepare(original, a.getId());
                a.setWidth(prepared.width());
                a.setHeight(prepared.height());
                a.setThumbnailPath(prepared.thumbnail().toString());
            }
            Asset saved = assets.save(a);

            Map<String, String> exif = images.exif(original);
            AssetMetadata m = new AssetMetadata();
            m.setAsset(saved);
            m.setExifJson(mapper.writeValueAsString(exif));
            m.setAiStatus(AiStatus.PENDING);
            m.setAiUpdatedAt(Instant.now());
            metas.save(m);
            eventPublisher.publishEvent(new AssetIndexedEvent(saved.getId()));

            return saved;
        } catch (IOException e) {
            throw new IllegalStateException("Asset indexing failed for " + original, e);
        }
    }


    private Set<Path> foldersToSkip(Path root, List<Path> tree, List<Path> images, boolean recursive) {
        Set<Path> foldersToSkip = new HashSet<>();
        if (!recursive) {
            return foldersToSkip;
        }
        tree.stream().filter(Files::isDirectory)
                    .filter(d -> !d.toAbsolutePath().normalize().equals(root))
                    .filter(d -> d.getFileName() != null
                            && (d.getFileName().toString().startsWith(".")
                            || d.getFileName().toString().startsWith("_")))
                    .forEach(hiddenDir -> {
                        Path normalized = hiddenDir.toAbsolutePath().normalize();
                        if (images.stream().noneMatch(image -> image.toAbsolutePath().normalize().startsWith(normalized))) {
                            foldersToSkip.add(normalized);
                        }
                    });
        return foldersToSkip;
    }

    private Map<String, Folder> resolveAlbums(Path root, Folder rootAlbum, List<Path> tree,
            Set<Path> foldersToSkip, boolean recursive) {
        Map<String, Folder> albumByDir = new HashMap<>();
        albumByDir.put(root.toString(), rootAlbum);
        if (!recursive) {
            return albumByDir;
        }
        tree.stream().filter(Files::isDirectory).sorted()
                    .filter(dir -> !isUnderFolderToSkip(dir, foldersToSkip))
                    .forEach(dir -> {
                        Path norm = dir.toAbsolutePath().normalize();
                        if (!albumByDir.containsKey(norm.toString())) {
                            albumByDir.put(norm.toString(), albumService.resolveAlbumForDir(norm, root, rootAlbum));
                        }
                    });
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

    public Optional<AssetMetadata> findMetadata(String id) {
        return metas.findById(id);
    }

    public List<Asset> findByFolder(String folderId) {
        return assets.findByFolderIdOrderByCreatedAtDesc(folderId);
    }

    public List<Asset> findAllById(Iterable<String> ids) {
        return (List<Asset>) assets.findAllById(ids);
    }

    public Optional<Folder> findFolder(String id) {
        return folders.findById(id);
    }

    public List<Folder> findAllFolders() {
        return folders.findAll();
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

    private record AssetIdentity(String folderId, String checksum, String storagePath) { }
    private record TreeScan(List<Path> directories, List<Path> images) { }
}
