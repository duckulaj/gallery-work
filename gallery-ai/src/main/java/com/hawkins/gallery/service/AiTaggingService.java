package com.hawkins.gallery.service;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class AiTaggingService {
    private static final ObjectMapper TAGS_MAPPER = new ObjectMapper();

    private final ChatClient chatClient;
    private final OllamaVisionService ollamaVision;
    private final KnownFaceService knownFaces;
    private final ObjectMapper mapper;

    /**
     * Central AI enrichment entry point for every image ingestion path.
     *
     * Uploads and server-directory indexing both call this method via
     * AssetService#createAsset(...). Keep future AI enhancements here so new
     * ingest sources automatically receive the same captioning/tagging behaviour.
     */
    public AiImageAnalysis analyzeImage(Path imagePath, String filename, Map<String, String> exif) {
        var vision = ollamaVision.analyze(imagePath, filename, exif);
        if (vision.isPresent()) {
            var result = vision.get();
            return new AiImageAnalysis(
                    result.caption(),
                    normaliseTags(result.tags()),
                    result.model(),
                    result.dominantColors(),
                    result.faceCount(),
                    result.faceNames(),
                    result.faceDescriptions(),
                    result.sceneType(),
                    result.sceneLabels());
        }

        String prompt = """
                Analyze this image file for a searchable gallery.
                Vision is unavailable; infer from filename and metadata.
                If the metadata suggests people, use the Known people context below to identify them.
                Metadata:
                Name: %s
                Path: %s
                EXIF: %s
                Known people: %s
                """.formatted(
                        sanitiseForPrompt(filename),
                        sanitiseForPrompt(safePath(imagePath)),
                        sanitiseForPrompt(summarizeExif(exif)),
                        sanitiseForPrompt(knownFaces.promptContext()));

        try {
            FallbackAnalysis fa = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .entity(FallbackAnalysis.class, spec -> spec.useProviderStructuredOutput());
            if (fa == null) {
                return fallback(filename);
            }
            return new AiImageAnalysis(
                    fa.caption() != null ? fa.caption() : "",
                    toJsonTags(fa.tags() != null ? fa.tags() : List.of()),
                    "spring-ai-chat",
                    "",
                    fa.faceCount(),
                    toJsonTags(fa.faceNames() != null ? fa.faceNames() : List.of()),
                    "[]",
                    fa.sceneType() != null ? fa.sceneType() : "",
                    toJsonTags(fa.sceneLabels() != null ? fa.sceneLabels() : List.of()));
        } catch (Exception ex) {
            return fallback(filename);
        }
    }

    private record FallbackAnalysis(
            String caption,
            List<String> tags,
            String sceneType,
            List<String> sceneLabels,
            int faceCount,
            List<String> faceNames) {}

    /**
     * Backwards-compatible helper for older call sites.
     */
    public String captionAndTags(String filename) {
        return analyzeImage(null, filename, Map.of()).combinedText();
    }

    /**
     * Strips newlines and other characters that could be used for prompt injection
     * before embedding user-controlled strings into an LLM prompt.
     */
    private static String sanitiseForPrompt(String input) {
        if (input == null) return "";
        return input.replaceAll("[\\r\\n\\t]", " ").trim();
    }

    private AiImageAnalysis fallback(String filename) {
        String caption = "Image file named " + filename;
        return new AiImageAnalysis(caption, toJsonTags(List.of(filename)), "fallback", "", 0, "[]", "[]", "", "[]");
    }

    private String normaliseTags(String rawTags) {
        if (rawTags == null || rawTags.isBlank()) {
            return "[]";
        }
        try {
            List<String> tags = mapper.readValue(rawTags, new TypeReference<List<String>>() {
            });
            return toJsonTags(tags);
        } catch (Exception ignored) {
            Set<String> tags = Arrays.stream(rawTags.split("[,|\\n]"))
                    .map(s -> s.replace("Caption:", "").replace("Tags:", "").trim())
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            return toJsonTags(tags.stream().toList());
        }
    }

    private String toJsonTags(List<String> tags) {
        try {
            return mapper.writeValueAsString(tags.stream()
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .toList());
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String tagsAsPlainText(String tags) {
        if (tags == null || tags.isBlank()) {
            return "";
        }
        try {
            return String.join(" ", TAGS_MAPPER.readValue(tags, new TypeReference<List<String>>() {
            }));
        } catch (Exception ignored) {
            return tags;
        }
    }

    private String safePath(Path imagePath) {
        return imagePath == null ? "unknown" : imagePath.toAbsolutePath().normalize().toString();
    }

    private String summarizeExif(Map<String, String> exif) {
        if (exif == null || exif.isEmpty()) {
            return "none";
        }
        return exif.entrySet().stream()
                .limit(20)
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("; "));
    }

    public record AiImageAnalysis(String caption, String tags, String model, String dominantColors,
            int faceCount, String faceNames, String faceDescriptions, String sceneType, String sceneLabels) {
        public String combinedText() {
            String tagText = tagsAsPlainText(tags);
            if (caption == null || caption.isBlank()) {
                return tagText;
            }
            if (tagText == null || tagText.isBlank()) {
                return caption;
            }
            return caption + "\n" + tagText + "\n"
                    + (dominantColors == null ? "" : dominantColors) + "\n"
                    + tagsAsPlainText(faceNames) + "\n"
                    + tagsAsPlainText(faceDescriptions) + "\n"
                    + (sceneType == null ? "" : sceneType) + "\n"
                    + tagsAsPlainText(sceneLabels);
        }
    }
}
