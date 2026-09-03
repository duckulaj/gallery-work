package com.hawkins.gallery.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hawkins.gallery.domain.ImportFailure;

public interface ImportFailureRepository extends JpaRepository<ImportFailure, String> {
    boolean existsBySourcePathAndSizeBytesAndLastModifiedAt(String sourcePath, long sizeBytes, Instant lastModifiedAt);
    List<ImportFailure> findBySourcePathIn(Collection<String> sourcePaths);
}
