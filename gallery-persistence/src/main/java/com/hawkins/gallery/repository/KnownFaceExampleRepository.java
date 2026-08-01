package com.hawkins.gallery.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hawkins.gallery.domain.KnownFaceExample;

public interface KnownFaceExampleRepository extends JpaRepository<KnownFaceExample, String> {

    @org.springframework.data.jpa.repository.Query(
        "SELECT e FROM KnownFaceExample e JOIN FETCH e.person ORDER BY e.createdAt DESC LIMIT 50")
    List<KnownFaceExample> findTop50ByOrderByCreatedAtDesc();
    List<KnownFaceExample> findByPersonIdOrderByCreatedAtDesc(String personId);
    boolean existsByPersonIdAndSourceAssetId(String personId, String sourceAssetId);
}
