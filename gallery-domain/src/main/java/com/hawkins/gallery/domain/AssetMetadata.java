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
import jakarta.persistence.Transient;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "asset_metadata")
@Getter
@Setter
@NoArgsConstructor
public class AssetMetadata {
    @Id
    @Column(name = "asset_id")
    private String assetId;
    private String title;
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String description;
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "ai_caption")
    private String aiCaption;
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "ai_tags")
    private String aiTags;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "exif_json", columnDefinition = "json")
    private String exifJson;
    @Column(name = "dominant_colors")
    private String dominantColors;
    @Column(name = "face_count")
    private Integer faceCount;
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "face_names")
    private String faceNames;
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "face_descriptions")
    private String faceDescriptions;
    @Column(name = "scene_type")
    private String sceneType;
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "scene_labels")
    private String sceneLabels;
    @Column(name = "ai_status", length = 24)
    @Enumerated(EnumType.STRING)
    private AiStatus aiStatus;
    @Column(name = "ai_model")
    private String aiModel;
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "ai_error")
    private String aiError;
    @Column(name = "ai_updated_at")
    private Instant aiUpdatedAt;
    @Column(name = "timing_exif_ms")
    private Long timingExifMs;
    @Column(name = "timing_ai_ms")
    private Long timingAiMs;
    @Column(name = "timing_face_ms")
    private Long timingFaceMs;
    @Column(name = "timing_embed_ms")
    private Long timingEmbedMs;
    @Column(name = "timing_total_ms")
    private Long timingTotalMs;
    @Column(name = "known_face_version", nullable = false)
    private long knownFaceVersion;
    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "asset_id")
    private Asset asset;

    @Transient
    public String getFaceNamesDisplay() {
        return formatJsonList(faceNames);
    }

    @Transient
    public String getFaceDescriptionsDisplay() {
        return formatJsonList(faceDescriptions);
    }

    private String formatJsonList(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return "";
        }
        // Simple JSON list parsing for display
        return json.replaceAll("[\\[\\]\"]", "").replace(",", ", ").trim();
    }
}
