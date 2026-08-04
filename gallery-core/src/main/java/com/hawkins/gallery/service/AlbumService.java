package com.hawkins.gallery.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hawkins.gallery.domain.Folder;
import com.hawkins.gallery.repository.AssetRepository;
import com.hawkins.gallery.repository.FolderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class AlbumService {

    private final FolderRepository folders;
    private final AssetRepository assetRepository;

    // ── Tree ─────────────────────────────────────────────────────────────────

    /**
     * Build the full album tree for the sidebar, marking which nodes contain
     * the currently active folder.
     */
    @Transactional(readOnly = true)
    public List<AlbumTreeNode> buildTree(String activeFolderId) {
        return folders.findByParentIsNullOrderByName().stream()
                .map(root -> buildNode(root, activeFolderId))
                .toList();
    }

    private AlbumTreeNode buildNode(Folder folder, String activeFolderId) {
        List<AlbumTreeNode> children = folders.findByParentOrderByName(folder).stream()
                .map(c -> buildNode(c, activeFolderId))
                .toList();
        boolean isActive = folder.getId().equals(activeFolderId);
        boolean containsActive = isActive || children.stream().anyMatch(AlbumTreeNode::containsActive);
        return new AlbumTreeNode(folder, children, containsActive);
    }

    // ── Path resolution ───────────────────────────────────────────────────────

    /**
     * Find or create a root album for a top-level indexed directory.
     * The album name is the last path component.
     */
    @Transactional
    public Folder resolveRootAlbum(Path dir) {
        String sourcePath = dir.toAbsolutePath().normalize().toString();
        return folders.findBySourcePath(sourcePath).orElseGet(() -> {
            Folder album = new Folder(dir.getFileName().toString(), null);
            album.setSourcePath(sourcePath);
            return folders.save(album);
        });
    }

    /**
     * Find or create a child album for {@code dir}, whose ancestor chain is
     * rooted at {@code rootDir} / {@code rootAlbum}.
     */
    @Transactional
    public Folder resolveAlbumForDir(Path dir, Path rootDir, Folder rootAlbum) {
        Path normalDir = dir.toAbsolutePath().normalize();
        Path normalRoot = rootDir.toAbsolutePath().normalize();
        if (normalDir.equals(normalRoot)) return rootAlbum;
        Folder parentAlbum = resolveAlbumForDir(dir.getParent(), rootDir, rootAlbum);
        String sourcePath = normalDir.toString();
        return folders.findBySourcePath(sourcePath).orElseGet(() -> {
            Folder child = new Folder(dir.getFileName().toString(), parentAlbum);
            child.setSourcePath(sourcePath);
            return folders.save(child);
        });
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @Transactional
    public Folder create(String name, String parentId) {
        Folder parent = (parentId != null && !parentId.isBlank())
                ? folders.findById(parentId).orElse(null) : null;
        return folders.save(new Folder(name, parent));
    }

    @Transactional
    public void delete(String id) {
        folders.findById(id).ifPresent(root -> {
            if (root.getParent() == null && "Gallery".equals(root.getName())) {
                log.warn("Attempted to delete root Gallery folder. Skipping.");
                return;
            }

            // Collect thumbnail paths before the delete so we can clean up the filesystem.
            // The recursive CTE fetches the whole subtree in one query.
            List<String> thumbnailPaths = assetRepository.findThumbnailPathsInSubtree(id);

            // Single DELETE — ON DELETE CASCADE on all FKs handles child folders,
            // assets, asset_metadata, and asset_embeddings automatically.
            folders.delete(root);
            folders.flush();

            // Clean up thumbnail files from disk.
            for (String path : thumbnailPaths) {
                try {
                    Files.deleteIfExists(Path.of(path));
                } catch (Exception e) {
                    log.warn("Failed to delete thumbnail {}: {}", path, e.getMessage());
                }
            }
        });
    }

    @Transactional
    public Folder rename(String id, String name) {
        Folder f = folders.findById(id).orElseThrow();
        f.setName(name);
        return folders.save(f);
    }

    // ── Types ─────────────────────────────────────────────────────────────────

    public record AlbumTreeNode(Folder folder, List<AlbumTreeNode> children, boolean containsActive) {}
}
