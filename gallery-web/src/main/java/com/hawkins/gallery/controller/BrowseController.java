package com.hawkins.gallery.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.hawkins.gallery.config.AppProperties;

@Controller
@SuppressWarnings("null")
public class BrowseController {

    private final List<Path> browseRoots;

    public BrowseController(AppProperties properties) {
        this.browseRoots = properties.importRoots().stream()
                .map(this::realOrNormalized)
                .distinct()
                .toList();
    }

    public record DirEntry(String name, String path) {
    }

    @GetMapping("/browse")
    public String browse(@RequestParam(defaultValue = "") String path,
            Model model) {
        Path fallback = browseRoots.getFirst();
        Path resolvedRequested;
        try {
            resolvedRequested = path.isBlank() ? fallback : Path.of(path).toRealPath();
        } catch (Exception ex) {
            resolvedRequested = fallback;
        }

        Path activeRoot = browseRoots.stream()
                .filter(resolvedRequested::startsWith)
                .max(Comparator.comparingInt(Path::getNameCount))
                .orElse(fallback);
        Path requested = resolvedRequested.startsWith(activeRoot) && Files.isDirectory(resolvedRequested)
                ? resolvedRequested : activeRoot;

        Path parentPath = requested.getParent();
        String parent = (parentPath != null && parentPath.startsWith(activeRoot))
                ? parentPath.toString() : null;

        List<DirEntry> subdirs = listDirectories(requested, activeRoot);

        model.addAttribute("browseRoots", browseRoots.stream()
                .map(root -> new DirEntry(root.toString(), root.toString()))
                .toList());
        model.addAttribute("currentPath", requested.toString());
        model.addAttribute("parentPath", parent);
        model.addAttribute("subdirs", subdirs);
        return "fragments/folder-picker :: picker-content";
    }

    private List<DirEntry> listDirectories(Path directory, Path activeRoot) {
        try (Stream<Path> children = Files.list(directory)) {
            return children
                    .filter(this::isVisibleDirectory)
                    .map(this::realOrNull)
                    .filter(java.util.Objects::nonNull)
                    .filter(child -> child.startsWith(activeRoot))
                    .sorted(Comparator.comparing(child -> child.getFileName().toString(),
                            String.CASE_INSENSITIVE_ORDER))
                    .map(child -> new DirEntry(child.getFileName().toString(), child.toString()))
                    .toList();
        } catch (IOException | SecurityException ex) {
            return List.of();
        }
    }

    private boolean isVisibleDirectory(Path path) {
        try {
            return Files.isDirectory(path) && !Files.isHidden(path);
        } catch (IOException | SecurityException ex) {
            return false;
        }
    }

    private Path realOrNull(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException | SecurityException ex) {
            return null;
        }
    }

    private Path realOrNormalized(Path path) {
        Path real = realOrNull(path);
        return real != null ? real : path.toAbsolutePath().normalize();
    }
}
