package com.hawkins.gallery.service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * HTTP client for the DeepFace ArcFace microservice (Python/FastAPI, port 8082).
 *
 * <p>Calls {@code POST /detect} with a multipart image and returns a list of
 * detected faces, each carrying a 512-D ArcFace embedding and a base-64 JPEG crop.
 *
 * <p>All failures are caught and logged; callers always receive an empty list on error
 * so face detection never blocks the main enrichment pipeline.
 */
@Slf4j
@RequiredArgsConstructor
public class DeepFaceClient {

    public record DetectedFace(
            Map<String, Integer> bbox,
            float confidence,
            float[] embedding,
            byte[] cropJpeg
    ) {}

    private final RestClient restClient;
    private final ObjectMapper mapper;

    /** Detect all faces in {@code imagePath}. Returns an empty list if the service is
     *  unavailable or the image contains no detectable faces. */
    public List<DetectedFace> detect(Path imagePath) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new FileSystemResource(imagePath));

            String json = restClient.post()
                    .uri("/detect")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            return parseFaces(json);

        } catch (ResourceAccessException ex) {
            log.warn("DeepFace service unavailable ({}): face detection skipped for {}",
                    ex.getMessage(), imagePath.getFileName());
            return List.of();
        } catch (Exception ex) {
            log.error("DeepFace detect error for {}: {}", imagePath.getFileName(), ex.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<DetectedFace> parseFaces(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        Map<String, Object> root = mapper.readValue(json, new TypeReference<>() {});
        List<Map<String, Object>> faces = (List<Map<String, Object>>) root.get("faces");
        if (faces == null || faces.isEmpty()) {
            return List.of();
        }

        return faces.stream().map(f -> {
            Map<String, Integer> bbox = (Map<String, Integer>) f.get("bbox");
            float confidence = ((Number) f.get("confidence")).floatValue();

            List<Number> embList = (List<Number>) f.get("embedding");
            float[] embedding = new float[embList.size()];
            for (int i = 0; i < embList.size(); i++) {
                embedding[i] = embList.get(i).floatValue();
            }

            String b64 = (String) f.get("crop_b64");
            byte[] crop = (b64 != null && !b64.isEmpty())
                    ? java.util.Base64.getDecoder().decode(b64)
                    : new byte[0];

            return new DetectedFace(bbox, confidence, embedding, crop);
        }).toList();
    }
}
