package com.hawkins.gallery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hawkins.gallery.domain.Folder;

public interface FolderRepository extends JpaRepository<Folder, String> {
    List<Folder> findByParentIdOrderByName(String parentId);

    Optional<Folder> findByNameAndParentId(String name, String parentId);

    Optional<Folder> findBySourcePath(String sourcePath);

    List<Folder> findByParentIsNullOrderByName();

    List<Folder> findByParentOrderByName(Folder parent);
}
