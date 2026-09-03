package com.hawkins.gallery.domain;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "import_failures")
@Getter
@Setter
@NoArgsConstructor
public class ImportFailure {
    @Id
    @Column(name = "source_path", length = 1024)
    private String sourcePath;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "last_modified_at", nullable = false)
    private Instant lastModifiedAt;

    @Column(name = "reason", length = 255, nullable = false)
    private String reason;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "detail")
    private String detail;

    @Column(name = "failed_at", nullable = false)
    private Instant failedAt = Instant.now();

    public ImportFailure(String sourcePath, long sizeBytes, Instant lastModifiedAt, String reason, String detail) {
        this.sourcePath = sourcePath;
        this.sizeBytes = sizeBytes;
        this.lastModifiedAt = lastModifiedAt;
        this.reason = reason;
        this.detail = detail;
        this.failedAt = Instant.now();
    }
}
