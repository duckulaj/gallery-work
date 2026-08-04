package com.hawkins.gallery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hawkins.gallery.domain.KnownFaceExample;

public interface KnownFaceExampleRepository extends JpaRepository<KnownFaceExample, String> {

    @org.springframework.data.jpa.repository.Query(
        "SELECT e FROM KnownFaceExample e JOIN FETCH e.person ORDER BY e.createdAt DESC LIMIT 50")
    List<KnownFaceExample> findTop50ByOrderByCreatedAtDesc();
    List<KnownFaceExample> findByPersonIdOrderByCreatedAtDesc(String personId);
    Optional<KnownFaceExample> findByPersonIdAndSourceAssetId(String personId, String sourceAssetId);
    boolean existsByPersonIdAndSourceAssetId(String personId, String sourceAssetId);
}
