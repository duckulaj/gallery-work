package com.hawkins.gallery.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StopWatch;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hawkins.gallery.config.AppProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaVisionService {
    private final ChatClient chatClient;
    private final AppProperties props;
    private final KnownFaceService knownFaces;
    private final ImageService images;
    private final ObjectMapper mapper;

    public Optional<VisionImageAnalysis> analyze(Path imagePath, String filename, Map<String, String> exif) {
        if (!props.ai().vision().enabled()) {
            return Optional.empty();
        }
        if (imagePath == null || !Files.isRegularFile(imagePath)) {
            return Optional.empty();
        }

        try {
            StopWatch sw = new StopWatch("Vision analysis for " + filename);

            sw.start("Image resizing");
            byte[] resizedImage = images.resizeForAi(imagePath);
            sw.stop();

            String prompt = """
                    Analyze this image for a photo gallery.
                    CRITICAL: Identify faces using the "Known people" context provided below.
                    If a face matches a description or name in the context, use that name in face_names.
                    Provide a concise description and relevant tags.
                    Limit lists (tags, colors, scene_labels) to 8 items each.

                    Context:
                    Name: %s
                    Known people: %s
                    """.formatted(filename, knownFaces.promptContext());

            sw.start("Ollama inference (" + props.ai().vision().model() + ")");
            VisionResponse visionResponse = chatClient.prompt()
                    .options(OllamaChatOptions.builder()
                            .model(props.ai().vision().model())
                            .temperature(props.ai().vision().temperature())
                            .numPredict(props.ai().vision().numPredict())
                            .numCtx(props.ai().vision().numCtx())
                            .numBatch(props.ai().vision().numBatch())
                            .topK(props.ai().vision().topK())
                            .topP(props.ai().vision().topP())
                            .repeatPenalty(props.ai().vision().repeatPenalty())
                            .keepAlive(props.ai().vision().keepAlive())
                            .disableThinking())
                    .user(u -> u.text(prompt)
                            .media(MimeTypeUtils.IMAGE_JPEG, new ByteArrayResource(resizedImage)))
                    .call()
                    .entity(VisionResponse.class, spec -> spec
                            .useProviderStructuredOutput()
                            .validateSchema());
            sw.stop();

            log.info("{}", sw.shortSummary());

            if (visionResponse == null) {
                log.warn("Vision model returned no response for {}", filename);
                return Optional.empty();
            }

            return Optional.of(toVisionAnalysis(visionResponse));
        } catch (Exception ex) {
            log.error("Vision analysis failed for {}: {}", filename, ex.getMessage());
            return Optional.empty();
        }
    }

    private VisionImageAnalysis toVisionAnalysis(VisionResponse vr) {
        String caption = sanitise(vr.caption());
        if ("description".equalsIgnoreCase(caption) || "Short descriptive caption".equalsIgnoreCase(caption)) {
            caption = "";
        }

        List<String> colors = sanitiseList(vr.colors());
        List<String> tags = sanitiseList(vr.tags());
        String sceneType = sanitise(vr.sceneType());
        List<String> sceneLabels = sanitiseList(vr.sceneLabels());
        List<String> faceNames = sanitiseList(vr.faceNames());
        List<String> faceDescriptions = sanitiseList(vr.faceDescriptions());
        int faceCount = vr.faceCount() == null ? 0 : vr.faceCount();
        faceCount = faceCount == 0 && !faceNames.isEmpty() ? faceNames.size() : faceCount;

        List<String> allTags = Stream.of(colors, tags, sceneLabels, List.of(sceneType), faceNames, faceDescriptions)
                .flatMap(List::stream)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();

        try {
            return new VisionImageAnalysis(
                    caption,
                    mapper.writeValueAsString(allTags),
                    String.join(", ", colors),
                    faceCount,
                    mapper.writeValueAsString(faceNames),
                    mapper.writeValueAsString(faceDescriptions),
                    sceneType,
                    String.join(", ", sceneLabels),
                    caption,
                    "ollama-vision:" + props.ai().vision().model());
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialise vision analysis", e);
        }
    }

    private String sanitise(String value) {
        if (value == null || value.isBlank()) return "";
        String trimmed = value.trim();
        return isPlaceholder(trimmed) ? "" : trimmed;
    }

    private List<String> sanitiseList(List<String> items) {
        if (items == null) return List.of();
        return items.stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .filter(s -> !isPlaceholder(s))
                .distinct()
                .toList();
    }

    private boolean isPlaceholder(String s) {
        String lower = s.toLowerCase();
        return lower.equals("names") || lower.equals("descriptions") || lower.equals("labels") ||
                lower.equals("tags") || lower.equals("colors") || lower.equals("description");
    }

    public record VisionImageAnalysis(
            String caption,
            String tags,
            String dominantColors,
            int faceCount,
            String faceNames,
            String faceDescriptions,
            String sceneType,
            String sceneLabels,
            String searchableText,
            String model) {
    }

    @JsonPropertyOrder({"caption", "colors", "tags", "scene_type", "scene_labels",
            "face_count", "face_names", "face_descriptions"})
    private record VisionResponse(
            String caption,
            List<String> colors,
            List<String> tags,
            @JsonProperty("scene_type") String sceneType,
            @JsonProperty("scene_labels") List<String> sceneLabels,
            @JsonProperty("face_count") Integer faceCount,
            @JsonProperty("face_names") List<String> faceNames,
            @JsonProperty("face_descriptions") List<String> faceDescriptions) {
    }
}
