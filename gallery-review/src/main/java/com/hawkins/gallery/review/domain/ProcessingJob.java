package com.hawkins.gallery.review.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "processing_job", uniqueConstraints = @UniqueConstraint(name="uq_processing_job_asset_type", columnNames={"asset_id","job_type"}))
@Getter @Setter @NoArgsConstructor
public class ProcessingJob {
    @Id private UUID id;
    @Column(name="asset_id", nullable=false, length=64) private String assetId;
    @Enumerated(EnumType.STRING) @Column(name="job_type", nullable=false) private JobType jobType;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private JobStatus status = JobStatus.PENDING;
    private int priority = 100;
    private int attempts;
    private int maxAttempts = 3;
    @Column(columnDefinition="text") private String lastError;
    private Instant availableAt = Instant.now();
    private Instant startedAt;
    private Instant completedAt;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @PrePersist void create() { if (id == null) id = UUID.randomUUID(); updatedAt = Instant.now(); }
    @PreUpdate void update() { updatedAt = Instant.now(); }
}
