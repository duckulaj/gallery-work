package com.hawkins.gallery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hawkins.gallery.domain.FaceDetection;

public interface FaceDetectionRepository extends JpaRepository<FaceDetection, String> {

        interface KnownFaceMatch {
                String getExampleId();
                String getPersonId();
                String getDisplayName();
                Double getSimilarity();
        }

    List<FaceDetection> findByAssetIdOrderByCreatedAtAsc(String assetId);

    void deleteByAssetId(String assetId);

    @Query("SELECT COUNT(f) FROM FaceDetection f WHERE f.assetId = :assetId AND f.personId IS NULL")
    long countUnidentifiedByAssetId(@Param("assetId") String assetId);

    /** Returns the IDs of all assets that have at least one unidentified face detection. */
    @Query(value = "SELECT DISTINCT f.asset_id FROM face_detections f WHERE f.person_id IS NULL",
           nativeQuery = true)
    List<String> findAssetIdsWithUnidentifiedFaces();

    /**
     * Finds the closest known-face example to the given ArcFace embedding vector.
     * Returns columns: [example_id, person_id, display_name, similarity (0–1)].
     * Only considers examples that have a stored embedding.
     */
    @Query(value = """
             SELECT kfe.id AS "exampleId", kfe.person_id AS "personId",
                     p.display_name AS "displayName",
                   1 - (kfe.embedding <=> CAST(:lit AS vector)) AS similarity
            FROM known_face_examples kfe
            JOIN known_persons p ON p.id = kfe.person_id
            WHERE kfe.embedding IS NOT NULL
            ORDER BY kfe.embedding <=> CAST(:lit AS vector)
            LIMIT 1
            """, nativeQuery = true)
    Optional<KnownFaceMatch> findNearestKnownFace(@Param("lit") String vectorLiteral);

    /**
     * Copies the ArcFace embedding from a face_detection row into a known_face_example row.
     * Used during enrollment so the user-labelled example immediately participates
     * in future vector-similarity searches.
     */
    @Modifying
    @Query(value = """
            UPDATE known_face_examples
            SET embedding = (
                SELECT CAST(fd.embedding_json AS vector)
                FROM face_detections fd
                WHERE fd.id = :detectionId
            )
            WHERE id = :exampleId
            """, nativeQuery = true)
        int copyEmbeddingToExample(@Param("detectionId") String detectionId,
                                                           @Param("exampleId") String exampleId);
}
