package com.hawkins.gallery.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;

class AppPropertiesTest {

    @Test
    void rejectsInvalidThresholdsAndThreadCounts() {
        var properties = new AppProperties(
                Path.of("/tmp/gallery"),
                java.util.List.of(Path.of("/tmp/imports")),
                420,
                5000,
                new AppProperties.Ai(
                        new AppProperties.Background(true, 6, 4000, 0, 6),
                        null,
                        null,
                        new AppProperties.Nsfw(true, "http://localhost:8082", -0.1, 1.1,
                                Path.of("/tmp/quarantine"))));

        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            var violations = validatorFactory.getValidator().validate(properties);
            assertThat(violations)
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("ai.background.embeddingThreads", "ai.nsfw.reviewThreshold", "ai.nsfw.explicitThreshold");
        }
    }

    @Test
    void acceptsAbsoluteConfiguredPaths() {
        var properties = new AppProperties(Path.of("/var/lib/gallery"),
                java.util.List.of(Path.of("/srv/photos")), 420, 5000, null);

        assertThat(properties.storageRoot()).isAbsolute();
        assertThat(properties.storageRoot().resolve("thumbs")).isEqualTo(Path.of("/var/lib/gallery/thumbs"));
    }
}
