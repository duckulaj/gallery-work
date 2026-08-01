package com.hawkins.gallery.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "known_persons")
@Getter
@Setter
@NoArgsConstructor
public class KnownPerson {
    @Id
    private String id = UUID.randomUUID().toString();

    @Column(name = "display_name", nullable = false, unique = true)
    private String displayName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public KnownPerson(String displayName) {
        this.displayName = displayName;
    }
}
