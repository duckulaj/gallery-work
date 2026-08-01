package com.hawkins.gallery.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "known_face_examples")
@Getter
@Setter
@NoArgsConstructor
public class KnownFaceExample {
    @Id
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "person_id", nullable = false)
    private KnownPerson person;

    @Column(name = "source_asset_id")
    private String sourceAssetId;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "face_description")
    private String faceDescription;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
