package com.hawkins.gallery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(String storageRoot, int thumbnailSize, int semanticCandidateLimit, Ai ai) {
    public Ai ai() {
        return ai == null ? Ai.defaults() : ai;
    }

    public record Ai(Vision vision, FaceRecognition faceRecognition, Nsfw nsfw) {
        static Ai defaults() {
            return new Ai(Vision.defaults(), FaceRecognition.defaults(), Nsfw.defaults());
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

    public record Nsfw(boolean enabled, String serviceUrl, double reviewThreshold, double explicitThreshold, String quarantineDir) {
        static Nsfw defaults() {
            return new Nsfw(true, "http://localhost:8082", 0.55, 0.85, "./data/gallery-quarantine");
        }
    }

    public record FaceRecognition(
            boolean enabled,
            String serviceUrl,
            double threshold,
            String faceCropsDir) {
        static FaceRecognition defaults() {
            return new FaceRecognition(false, "http://localhost:8082", 0.40, "./data/gallery/face-crops");
        }
    }

    public record Vision(boolean enabled, String model,
                          double temperature, int numPredict, int resizeSize,
                          int numCtx, int topK, double topP, double repeatPenalty,
                          int numBatch, String keepAlive) {
        static Vision defaults() {
            return new Vision(false, "qwen3.8",
                    0.0d, 768, 672, 4096, 40, 0.9d, 1.1d, 512, "10m");
        }
    }
}
