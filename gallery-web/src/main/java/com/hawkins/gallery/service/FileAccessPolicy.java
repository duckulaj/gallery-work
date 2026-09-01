package com.hawkins.gallery.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Service;

import com.hawkins.gallery.config.AppProperties;

@Service
public class FileAccessPolicy {
    private final List<Path> roots;

    public FileAccessPolicy(AppProperties properties) {
        var configured = new java.util.ArrayList<Path>();
        configured.add(properties.storageRoot());
        configured.addAll(properties.importRoots());
        this.roots = configured.stream().map(this::realOrNormalized).toList();
    }

    public Path requireReadableFile(String rawPath) throws IOException {
        if (rawPath == null || rawPath.isBlank()) throw new IOException("File path is not set");
        Path candidate = Path.of(rawPath).toRealPath();
        if (!Files.isRegularFile(candidate) || roots.stream().noneMatch(candidate::startsWith)) {
            throw new IOException("File is outside configured gallery roots");
        }
        return candidate;
    }

    private Path realOrNormalized(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException ex) {
            return path.toAbsolutePath().normalize();
        }
    }
}
