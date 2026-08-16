package com.hawkins.gallery.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ImageServiceTest {
    @Test
    void sanitiseExifTextRemovesPostgresIncompatibleNullCharacters() {
        assertThat(ImageService.sanitiseExifText("IICSA\u0000comment"))
                .isEqualTo("IICSAcomment");
    }
}