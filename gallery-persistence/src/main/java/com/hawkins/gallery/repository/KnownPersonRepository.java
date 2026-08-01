package com.hawkins.gallery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.hawkins.gallery.domain.KnownPerson;

public interface KnownPersonRepository extends JpaRepository<KnownPerson, String> {
    Optional<KnownPerson> findByDisplayNameIgnoreCase(String displayName);

    /** Returns [id, displayName, exampleCount] tuples without N+1 queries. */
    @Query("SELECT p.id, p.displayName, COUNT(e) FROM KnownPerson p LEFT JOIN KnownFaceExample e ON e.person = p GROUP BY p.id, p.displayName ORDER BY p.displayName")
    List<Object[]> findAllWithExampleCount();
}
