package com.hawkins.gallery.review.domain;

import java.time.Instant;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "asset_review")
@Getter @Setter @NoArgsConstructor
public class AssetReview {
    @Id
    @Column(name = "asset_id", length = 64)
    private String assetId;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "nsfw_score")
    private Double nsfwScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "nsfw_level", nullable = false)
    private NsfwLevel nsfwLevel = NsfwLevel.UNKNOWN;

    @Column(name = "detector_version")
    private Integer detectorVersion;

    @Column(name = "detector_labels_json", nullable = false, columnDefinition = "text")
    private String detectorLabelsJson = "[]";

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false)
    private ReviewStatus reviewStatus = ReviewStatus.PENDING;

    @Column(name = "manual_override", nullable = false)
    private boolean manualOverride;

    @Column(name = "original_path", columnDefinition = "text")
    private String originalPath;

    @Column(name = "quarantine_path", columnDefinition = "text")
    private String quarantinePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_status", nullable = false)
    private FileOperationStatus operationStatus = FileOperationStatus.NONE;

    @Column(name = "operation_error", columnDefinition = "text")
    private String operationError;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    private Instant analysedAt;
    private Instant reviewedAt;
    private Instant updatedAt = Instant.now();

    @PrePersist @PreUpdate
    void touch() { updatedAt = Instant.now(); }
}
