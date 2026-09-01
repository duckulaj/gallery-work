package com.hawkins.gallery.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hawkins.gallery.domain.AiStatus;
import com.hawkins.gallery.domain.AssetMetadata;
import com.hawkins.gallery.domain.KnownFaceExample;
import com.hawkins.gallery.domain.KnownPerson;
import com.hawkins.gallery.repository.AssetMetadataRepository;
import com.hawkins.gallery.repository.FaceDetectionRepository;
import com.hawkins.gallery.repository.KnownFaceExampleRepository;
import com.hawkins.gallery.repository.KnownPersonRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class KnownFaceService {
    private final KnownPersonRepository people;
    private final KnownFaceExampleRepository examples;
    private final FaceDetectionRepository detections;
    private final AssetMetadataRepository metas;
    private final ObjectMapper mapper;

    @Transactional(readOnly = true)
    public String promptContext() {
        List<KnownFaceExample> recent = examples.findTop50ByOrderByCreatedAtDesc();
        if (recent.isEmpty()) {
            return "No known people have been labelled yet.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Known people in this private gallery. Use these examples only as local user-provided context; do not guess identity without visual similarity.\n");
        for (KnownFaceExample example : recent) {
            String name = example.getPerson().getDisplayName();
            String desc = safe(example.getFaceDescription());
            sb.append("- ").append(name).append(": ").append(desc.isBlank() ? "labelled by user" : desc).append('\n');
        }
        return sb.toString();
    }

    @Transactional
    public KnownFaceResult addExample(String assetId, String displayName, String faceDescription) {
        String cleanName = clean(displayName);
        if (cleanName.isBlank()) {
            throw new IllegalArgumentException("Person name is required");
        }

        KnownPerson person = people.findByDisplayNameIgnoreCase(cleanName)
                .orElseGet(() -> people.save(new KnownPerson(cleanName)));

        var assetFaces = detections.findByAssetIdOrderByCreatedAtAsc(assetId);
        if (assetFaces.size() != 1) {
            throw new IllegalArgumentException(
                    "Select Identify on the intended face when an image contains zero or multiple detected faces");
        }
        var detection = assetFaces.getFirst();
        if (detection.getEmbeddingJson() == null || detection.getEmbeddingJson().isBlank()) {
            throw new IllegalStateException("The detected face has no recognition embedding");
        }

        KnownFaceExample example = examples.findByPersonIdAndSourceAssetId(person.getId(), assetId)
                .orElseGet(() -> {
                    KnownFaceExample created = new KnownFaceExample();
                    created.setPerson(person);
                    created.setSourceAssetId(assetId);
                    return created;
                });
        example.setFaceDescription(clean(faceDescription));
        examples.saveAndFlush(example);
        if (detections.copyEmbeddingToExample(detection.getId(), example.getId()) != 1) {
            throw new IllegalStateException("Could not store the detected face embedding");
        }

        AssetMetadata meta = metas.findById(assetId).orElse(null);
        if (meta != null) {
            meta.setFaceNames(mergeJsonList(meta.getFaceNames(), cleanName));
            meta.setAiStatus(AiStatus.PENDING);
            meta.setAiError(null);
            meta.setAiUpdatedAt(Instant.now());
            metas.save(meta);
        }

        return new KnownFaceResult(cleanName, "Saved known face example for " + cleanName + ". Background AI will use it on future images.");
    }

    @Transactional(readOnly = true)
    public List<KnownPersonSummary> summaries() {
        return people.findAllWithExampleCount().stream()
                .map(row -> new KnownPersonSummary(
                        (String) row[0],
                        (String) row[1],
                        Math.toIntExact((Long) row[2])))
                .toList();
    }

    @Transactional(readOnly = true)
    public String mergeRecognisedNames(String existingJson, List<String> candidateNames, List<String> faceDescriptions) {
        Set<String> names = new LinkedHashSet<>(readJsonList(existingJson));
        if (candidateNames != null) {
            candidateNames.stream().map(this::clean).filter(s -> !s.isBlank()).forEach(names::add);
        }
        names.addAll(matchByDescription(faceDescriptions));
        return writeJsonList(names.stream().toList());
    }

    private List<String> matchByDescription(List<String> faceDescriptions) {
        if (faceDescriptions == null || faceDescriptions.isEmpty()) {
            return List.of();
        }
        List<KnownFaceExample> known = examples.findTop50ByOrderByCreatedAtDesc();
        List<String> matches = new ArrayList<>();
        for (String description : faceDescriptions) {
            Set<String> descTokens = tokens(description);
            if (descTokens.isEmpty()) {
                continue;
            }
            for (KnownFaceExample example : known) {
                Set<String> knownTokens = tokens(example.getFaceDescription());
                knownTokens.retainAll(descTokens);
                if (knownTokens.size() >= 3) {
                    matches.add(example.getPerson().getDisplayName());
                }
            }
        }
        return matches.stream().distinct().toList();
    }

    private Set<String> tokens(String value) {
        Set<String> result = new LinkedHashSet<>();
        if (value == null) {
            return result;
        }
        for (String token : value.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() > 2 && !Set.of("the", "and", "with", "person", "adult", "face", "unknown").contains(token)) {
                result.add(token);
            }
        }
        return result;
    }

    public String mergeJsonList(String json, String value) {
        List<String> values = new ArrayList<>(readJsonList(json));
        if (value != null && !value.isBlank() && values.stream().noneMatch(existing -> existing.equalsIgnoreCase(value))) {
            values.add(value);
        }
        return writeJsonList(values);
    }

    public List<String> readJsonList(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            return List.of(json);
        }
    }

    public String writeJsonList(List<String> values) {
        try {
            return mapper.writeValueAsString(values.stream()
                    .map(this::clean)
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .toList());
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record KnownFaceResult(String displayName, String message) {}
    public record KnownPersonSummary(String id, String displayName, int examples) {}
}
