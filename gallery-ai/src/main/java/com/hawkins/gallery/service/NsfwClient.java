package com.hawkins.gallery.service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StopWatch;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class NsfwClient {
    public record Label(String name, double score) {
    }

    public record Profile(double uploadReadMs, double tempfileWriteMs, double modelInitMs,
            double inferenceMs, double scoringMs, double totalMs) {
        public static Profile empty() {
            return new Profile(0d, 0d, 0d, 0d, 0d, 0d);
        }
    }

    public record Result(double score, String level, List<Label> labels, Profile profile) {
        public static Result unknown() {
            return new Result(0d, "UNKNOWN", List.of(), Profile.empty());
        }
    }

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final boolean enabled;

    public Result detect(Path imagePath) {
        if (!enabled)
            return Result.unknown();

        StopWatch sw = new StopWatch("NSFW analysis for " + imagePath.getFileName());
        try {

            sw.start("NSFW Analysis stsrting");
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new FileSystemResource(imagePath));
            String json = restClient.post().uri("/nsfw/detect")
                    .contentType(MediaType.MULTIPART_FORM_DATA).body(body)
                    .retrieve().body(String.class);
            Map<String, Object> root = mapper.readValue(json, new TypeReference<>() {
            });
            double score = ((Number) root.getOrDefault("score", 0d)).doubleValue();
            String level = String.valueOf(root.getOrDefault("level", "UNKNOWN"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> raw = (List<Map<String, Object>>) root.getOrDefault("labels", List.of());
            List<Label> labels = raw.stream().map(x -> new Label(
                    String.valueOf(x.get("name")), ((Number) x.getOrDefault("score", 0d)).doubleValue())).toList();
                @SuppressWarnings("unchecked")
                Map<String, Object> rawProfile = (Map<String, Object>) root.getOrDefault("profile", Map.of());
                Profile profile = new Profile(
                    number(rawProfile, "uploadReadMs"),
                    number(rawProfile, "tempfileWriteMs"),
                    number(rawProfile, "modelInitMs"),
                    number(rawProfile, "inferenceMs"),
                    number(rawProfile, "scoringMs"),
                    number(rawProfile, "totalMs"));

            sw.stop();
                log.info("{} | server upload={}ms tempfile={}ms model-init={}ms inference={}ms scoring={}ms total={}ms",
                    imagePath.getFileName(), profile.uploadReadMs(), profile.tempfileWriteMs(),
                    profile.modelInitMs(), profile.inferenceMs(), profile.scoringMs(), profile.totalMs());

                return new Result(score, level, labels, profile);
        } catch (Exception ex) {
            log.warn("NSFW detection skipped for {}: {}", imagePath.getFileName(), ex.getMessage());
            sw.stop();
            log.info("{}", sw.shortSummary());
            return Result.unknown();
        }
    }

    private double number(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Number number ? number.doubleValue() : 0d;
    }
}
