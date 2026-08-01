package com.hawkins.gallery.domain;

import java.time.Instant;
import java.util.UUID;

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
@Table(name = "folders")
@Getter
@Setter
@NoArgsConstructor
public class Folder {
    @Id
    private String id = UUID.randomUUID().toString();
    @Column(nullable = false)
    private String name;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Folder parent;
    @Column(name = "source_path", length = 1024)
    private String sourcePath;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Folder(String name, Folder parent) {
        this.name = name;
        this.parent = parent;
    }
}
