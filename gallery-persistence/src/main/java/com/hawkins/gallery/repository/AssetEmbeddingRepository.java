package com.hawkins.gallery.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.hawkins.gallery.domain.AssetEmbedding;

public interface AssetEmbeddingRepository extends JpaRepository<AssetEmbedding, String> {

    @Query("select e from AssetEmbedding e join fetch e.asset a where a.folder.id=:folderId")
    List<AssetEmbedding> findByFolder(@Param("folderId") String folderId);

    /**
     * PostgreSQL/pgvector nearest-neighbour search.
     *
     * Expects asset_embeddings.embedding to be a pgvector column, for example:
     *   embedding vector(768)
     *
     * The query parameter is passed as a pgvector literal, for example:
     *   [0.1,0.2,0.3]
     *
     * pgvector's <=> operator returns cosine distance, so semantic_score converts
     * that distance into a score where higher is better.
     */
    @Query(value = """
            select
                a.id as assetId,
                greatest(0.0, 1.0 - (e.embedding <=> cast(:queryEmbedding as vector))) as semanticScore
            from asset_embeddings e
            join assets a on a.id = e.asset_id
            where a.folder_id = :folderId
              and e.embedding is not null
            order by e.embedding <=> cast(:queryEmbedding as vector)
            limit :limit
            """, nativeQuery = true)
    List<SemanticAssetScoreRow> findNearestByFolder(
            @Param("folderId") String folderId,
            @Param("queryEmbedding") String queryEmbedding,
            @Param("limit") int limit);

    /**
     * Upsert the native pgvector embedding while also retaining embedding_json as
     * a temporary compatibility/audit column during the MySQL -> PostgreSQL move.
     */
    @Modifying
    @Transactional
    @Query(value = """
            insert into asset_embeddings(asset_id, model, dimensions, embedding, embedding_json, created_at)
            values (:assetId, :model, :dimensions, cast(:embedding as vector), cast(:embeddingJson as json), now())
            on conflict (asset_id) do update set
                model = excluded.model,
                dimensions = excluded.dimensions,
                embedding = excluded.embedding,
                embedding_json = excluded.embedding_json,
                created_at = now()
            """, nativeQuery = true)
    void upsertVector(
            @Param("assetId") String assetId,
            @Param("model") String model,
            @Param("dimensions") int dimensions,
            @Param("embedding") String embedding,
            @Param("embeddingJson") String embeddingJson);

    interface SemanticAssetScoreRow {
        String getAssetId();
        Float getSemanticScore();
    }
}
