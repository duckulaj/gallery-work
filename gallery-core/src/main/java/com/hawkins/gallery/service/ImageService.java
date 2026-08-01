package com.hawkins.gallery.service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.hawkins.gallery.config.AppProperties;

import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;

@Slf4j
@Service
public class ImageService {
    private final AppProperties props;

    public ImageService(AppProperties props) {
        this.props = props;
    }

    public void ensureDirs() throws IOException {
        Files.createDirectories(Path.of(props.storageRoot(), "originals"));
        Files.createDirectories(Path.of(props.storageRoot(), "thumbs"));
    }

    public String sha256(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                md.update(buf, 0, read);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public int[] dimensions(Path p) {
        try {
            BufferedImage img = ImageIO.read(p.toFile());
            return img == null ? new int[] { 0, 0 } : new int[] { img.getWidth(), img.getHeight() };
        } catch (Exception e) {
            return new int[] { 0, 0 };
        }
    }

    public Path thumbnail(Path original, String id) throws IOException {
        ensureDirs();
        Path out = Path.of(props.storageRoot(), "thumbs", id + ".jpg");
        Thumbnails.of(original.toFile())
                .size(props.thumbnailSize(), props.thumbnailSize())
                .keepAspectRatio(true)
                .outputQuality(0.85)
                .outputFormat("jpg")
                .toFile(out.toFile());
        return out;
    }

    public byte[] resizeForAi(Path original) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int size = props.ai().vision().resizeSize();
        Thumbnails.of(original.toFile())
                .size(size, size)
                .outputFormat("jpg")
                .toOutputStream(out);
        return out.toByteArray();
    }

    public Map<String, String> exif(Path p) {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            Metadata md = ImageMetadataReader.readMetadata(p.toFile());
            md.getDirectories().forEach(d -> {
                for (Tag t : d.getTags())
                    if (map.size() < 80)
                        map.put(d.getName() + "." + t.getTagName(), t.getDescription());
            });
        } catch (Exception e) {
            log.debug("Could not read EXIF for {}: {}", p.getFileName(), e.getMessage());
        }
        return map;
    }
}
