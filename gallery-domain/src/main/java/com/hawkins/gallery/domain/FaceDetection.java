package com.hawkins.gallery.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One row per face detected by the DeepFace microservice in a single asset.
 * The embedding is stored as raw JSON text so it can be read back for enrollment;
 * similarity search uses the {@code known_face_examples.embedding} pgvector column.
 */
@Entity
@Table(name = "face_detections")
@Getter
@Setter
@NoArgsConstructor
public class FaceDetection {

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(name = "asset_id", nullable = false, length = 36)
    private String assetId;

    /** Bounding box JSON: {"x":100,"y":50,"w":80,"h":100} */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "bbox_json", nullable = false)
    private String bboxJson;

    /** 512-D ArcFace embedding serialised as a JSON float array. */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "embedding_json")
    private String embeddingJson;

    @Column(name = "person_id", length = 36)
    private String personId;

    @Column(name = "person_name")
    private String personName;

    /** Cosine similarity (0–1) at the time of recognition; null if unidentified. */
    @Column(name = "confidence")
    private Float confidence;

    @Column(name = "crop_path", length = 1024)
    private String cropPath;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
