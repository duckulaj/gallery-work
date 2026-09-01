package com.hawkins.gallery.config;

import java.nio.file.Path;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotEmpty;

@ConfigurationProperties(prefix = "app")
@Validated
public record AppProperties(
        @NotNull Path storageRoot,
        @NotEmpty List<@NotNull Path> importRoots,
        @Min(1) int thumbnailSize,
        @Min(1) int semanticCandidateLimit,
        @Valid Ai ai) {
    public Ai ai() {
        return ai == null ? Ai.defaults() : ai;
    }

    public record Ai(@Valid Background background, @Valid Vision vision,
                     @Valid FaceRecognition faceRecognition, @Valid Nsfw nsfw) {
        static Ai defaults() {
            return new Ai(Background.defaults(), Vision.defaults(), FaceRecognition.defaults(), Nsfw.defaults());
        }

        public Background background() {
            return background == null ? Background.defaults() : background;
        }

        public Vision vision() {
            return vision == null ? Vision.defaults() : vision;
        }

        public FaceRecognition faceRecognition() {
            return faceRecognition == null ? FaceRecognition.defaults() : faceRecognition;
        }
        public Nsfw nsfw() {
            return nsfw == null ? Nsfw.defaults() : nsfw;
        }
    }

    public record Background(
            boolean enabled,
            @Min(1) Integer maxInFlight,
            @Min(1) Integer batchSize,
            @Min(100) int fixedDelayMs,
            @Min(1) int embeddingThreads,
            @Min(1) int connectionPoolSize) {
        public int effectiveMaxInFlight() {
            if (maxInFlight != null) {
                return maxInFlight;
            }
            return batchSize != null ? batchSize : 6;
        }

        static Background defaults() {
            return new Background(true, 6, null, 4000, 4, 6);
        }
    }

    public record Nsfw(
            boolean enabled,
            @NotBlank String serviceUrl,
            @DecimalMin("0.0") @DecimalMax("1.0") double reviewThreshold,
            @DecimalMin("0.0") @DecimalMax("1.0") double explicitThreshold,
            @NotNull Path quarantineDir) {
        static Nsfw defaults() {
            return new Nsfw(true, "http://localhost:8082", 0.55, 0.85,
                    Path.of("data/gallery-quarantine").toAbsolutePath().normalize());
        }
    }

    public record FaceRecognition(
            boolean enabled,
            @NotBlank String serviceUrl,
            @DecimalMin("0.0") @DecimalMax("1.0") double threshold,
            @NotNull Path faceCropsDir) {
        static FaceRecognition defaults() {
            return new FaceRecognition(false, "http://localhost:8082", 0.40,
                    Path.of("data/gallery/face-crops").toAbsolutePath().normalize());
        }
    }

    public record Vision(boolean enabled, @NotBlank String model,
                          double temperature, @Min(1) int numPredict, @Min(1) int resizeSize,
                          @Min(1) int numCtx, @Min(1) int topK,
                          @DecimalMin("0.0") @DecimalMax("1.0") double topP,
                          @DecimalMin("0.0") double repeatPenalty,
                          @Min(1) int numBatch, @NotBlank String keepAlive) {
        static Vision defaults() {
            return new Vision(false, "qwen3.8",
                    0.0d, 768, 672, 4096, 40, 0.9d, 1.1d, 512, "10m");
        }
    }
}
