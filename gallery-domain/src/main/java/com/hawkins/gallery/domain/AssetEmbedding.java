package com.hawkins.gallery.domain;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "asset_embeddings")
@Getter
@Setter
@NoArgsConstructor
public class AssetEmbedding {
    @Id
    @Column(name = "asset_id")
    private String assetId;
    private String model;
    private int dimensions;
    /**
     * Native PostgreSQL/pgvector column.
     *
     * This is intentionally represented as a String in the JPA entity because
     * writes/searches are handled through native repository queries that cast
     * pgvector literals. That avoids adding a hard dependency on a Hibernate
     * vector type while the application is being migrated from MySQL.
     */
    @Column(name = "embedding", columnDefinition = "vector")
    private String embedding;

    /**
     * Temporary compatibility/audit copy of the vector. This lets the app keep
     * running during migration and gives SearchService a fallback path if the
     * pgvector column/index is not ready yet.
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "embedding_json", columnDefinition = "json")
    private String embeddingJson;
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "asset_id")
    private Asset asset;
}
