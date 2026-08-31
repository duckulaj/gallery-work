package com.hawkins.gallery.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.hawkins.gallery.service.AssetService;

import lombok.RequiredArgsConstructor;
@Controller
@RequiredArgsConstructor
public class NsfwController {
    private final AssetService assetService;

    @Value("${app.ai.nsfw.review-threshold:0.55}")
    private double defaultThreshold;

    @Value("${app.ai.nsfw.quarantine-dir:./data/gallery-quarantine}")
    private String quarantineDir;

    @GetMapping("/review/sensitive")
    public String review(@RequestParam(required = false) Double threshold) {
        double score = threshold == null ? defaultThreshold : threshold;
        return "redirect:/review?threshold=" + score;
    }

    @PostMapping("/review/sensitive/{id}/status")
    public String status(@PathVariable String id, @RequestParam String value,
                         @RequestParam(defaultValue = "0.55") double threshold) {
        assetService.setNsfwReviewStatus(id, value);
        return "redirect:/review/sensitive?threshold=" + threshold;
    }

    @PostMapping("/review/sensitive/{id}/quarantine")
    public String quarantine(@PathVariable String id,
                             @RequestParam(defaultValue = "0.55") double threshold) throws IOException {
        assetService.moveToQuarantine(id, Path.of(quarantineDir).toAbsolutePath().normalize());
        return "redirect:/review/sensitive?threshold=" + threshold;
    }

    private Path uniqueDestination(Path requested) {
        if (!Files.exists(requested)) return requested;
        String name = requested.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            Path candidate = requested.getParent().resolve(base + "-" + i + ext);
            if (!Files.exists(candidate)) return candidate;
        }
    }
}
