package com.hawkins.gallery.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedNativeQuery;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NamedNativeQuery(
    name = "Asset.fullTextSearch",
    query = "SELECT DISTINCT a.* FROM assets a LEFT JOIN asset_metadata m ON m.asset_id=a.id" +
            " WHERE a.folder_id=?1 AND (" +
            " to_tsvector('english', a.filename) @@ plainto_tsquery('english', ?2) OR" +
            " to_tsvector('english', coalesce(m.title,'') || ' ' || coalesce(m.description,'') || ' ' || coalesce(m.ai_caption,'') || ' ' || coalesce(m.ai_tags,'') || ' ' || coalesce(m.dominant_colors,'') || ' ' || coalesce(m.face_names,'') || ' ' || coalesce(m.face_descriptions,'') || ' ' || coalesce(m.scene_type,'') || ' ' || coalesce(m.scene_labels,'')) @@ plainto_tsquery('english', ?2) OR" +
            " a.filename ILIKE '%' || ?2 || '%' OR" +
            " m.title ILIKE '%' || ?2 || '%' OR" +
            " m.description ILIKE '%' || ?2 || '%' OR" +
            " m.ai_tags ILIKE '%' || ?2 || '%' OR" +
            " m.ai_caption ILIKE '%' || ?2 || '%' OR" +
            " m.dominant_colors ILIKE '%' || ?2 || '%' OR" +
            " m.face_names ILIKE '%' || ?2 || '%' OR" +
            " m.face_descriptions ILIKE '%' || ?2 || '%' OR" +
            " m.scene_type ILIKE '%' || ?2 || '%' OR" +
            " m.scene_labels ILIKE '%' || ?2 || '%'" +
            ") ORDER BY a.created_at DESC LIMIT 200",
    resultClass = Asset.class
)
@Entity
@Table(name = "assets")
@Getter
@Setter
@NoArgsConstructor
public class Asset {
    @Id
    private String id = UUID.randomUUID().toString();
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id", nullable = false)
    private Folder folder;
    @Column(nullable = false)
    private String filename;
    @Column(name = "content_type", nullable = false)
    private String contentType;
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;
    private Integer width;
    private Integer height;
    @Column(nullable = false)
    private String checksum;
    @Column(name = "storage_path", nullable = false, length = 1024)
    private String storagePath;
    @Column(name = "thumbnail_path", length = 1024)
    private String thumbnailPath;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}