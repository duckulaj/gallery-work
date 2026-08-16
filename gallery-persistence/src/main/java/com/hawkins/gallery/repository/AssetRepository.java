package com.hawkins.gallery.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hawkins.gallery.domain.Asset;

public interface AssetRepository extends JpaRepository<Asset, String> {
  List<Asset> findByFolderId(String folderId);

  /**
   * Returns all non-null thumbnail_path values for assets in the folder subtree
   * rooted at {@code rootId}, using a recursive CTE. Used to clean up thumbnail
   * files from disk before a cascading folder delete.
   */
  @Query(value = """
      WITH RECURSIVE subtree AS (
          SELECT id FROM folders WHERE id = :rootId
          UNION ALL
          SELECT f.id FROM folders f JOIN subtree s ON f.parent_id = s.id
      )
      SELECT a.thumbnail_path
        FROM assets a
       WHERE a.folder_id IN (SELECT id FROM subtree)
         AND a.thumbnail_path IS NOT NULL
      """, nativeQuery = true)
  List<String> findThumbnailPathsInSubtree(@Param("rootId") String rootId);

  List<Asset> findByFolderIdOrderByCreatedAtDesc(String folderId);

  List<Asset> fullTextSearch(String q);

  boolean existsByFolderIdAndChecksum(String folderId, String checksum);

  boolean existsByFolderIdAndStoragePath(String folderId, String storagePath);
  @Query(value = "SELECT a.* FROM assets a JOIN asset_metadata m ON m.asset_id=a.id WHERE m.nsfw_score >= :threshold AND (:status = 'ALL' OR m.nsfw_review_status = :status) ORDER BY m.nsfw_score DESC", nativeQuery = true)
  List<Asset> findNsfwReview(@Param("threshold") double threshold, @Param("status") String status);

}