package com.hawkins.gallery.controller;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class BrowseController {

    /** Restrict browsing to the current user's home directory to prevent exposing system paths. */
    private static final Path BROWSE_ROOT = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();

    public record DirEntry(String name, String path) {
    }

    @GetMapping("/browse")
    public String browse(@RequestParam(defaultValue = "") String path,
            Model model) {
        Path requested = path.isBlank() ? BROWSE_ROOT
                : Path.of(path).toAbsolutePath().normalize();

        // Sandbox: never navigate above the browse root
        if (!requested.startsWith(BROWSE_ROOT)) {
            requested = BROWSE_ROOT;
        }

        File dir = requested.toFile();
        if (!dir.exists() || !dir.isDirectory()) {
            dir = BROWSE_ROOT.toFile();
        }

        String canonical;
        try {
            canonical = dir.getCanonicalPath();
        } catch (Exception e) {
            canonical = dir.getAbsolutePath();
        }

        // Parent stays within the browse root
        Path parentPath = requested.getParent();
        String parent = (parentPath != null && parentPath.startsWith(BROWSE_ROOT))
                ? parentPath.toString() : null;

        // Build simple string-based entries so Thymeleaf never calls getCanonicalPath()
        File[] children = dir.listFiles(f -> f.isDirectory() && !f.isHidden());
        List<DirEntry> subdirs = children == null ? List.of()
                : Arrays.stream(children)
                        // Skip symlinks to prevent escaping the sandbox
                        .filter(f -> !Files.isSymbolicLink(f.toPath()))
                        .sorted(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER))
                        .map(f -> new DirEntry(f.getName(), f.getAbsolutePath()))
                        .toList();

        model.addAttribute("currentPath", canonical);
        model.addAttribute("parentPath", parent);
        model.addAttribute("subdirs", subdirs);
        return "fragments/folder-picker :: picker-content";
    }
}
