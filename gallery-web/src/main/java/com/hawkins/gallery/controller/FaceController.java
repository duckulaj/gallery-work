package com.hawkins.gallery.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import com.hawkins.gallery.repository.FaceDetectionRepository;
import com.hawkins.gallery.service.AssetService;
import com.hawkins.gallery.service.FaceDetectionService;
import com.hawkins.gallery.service.KnownFaceService;

import lombok.RequiredArgsConstructor;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Controller
@RequiredArgsConstructor
public class FaceController {

    private final AssetService assetService;
    private final FaceDetectionService faceDetectionService;
    private final FaceDetectionRepository faceDetectionRepo;
    private final KnownFaceService knownFaces;

    /**
     * Workspace view filtered to assets with unidentified faces.
     * Replaces the main workspace fragment via HTMX or returns a full page.
     */
    @GetMapping("/faces/unidentified")
    public String unidentifiedFaces(Model model) {
        List<String> assetIds = faceDetectionRepo.findAssetIdsWithUnidentifiedFaces();
        var assets = assetService.findAllById(assetIds);
        model.addAttribute("assets", assets);
        model.addAttribute("unidentifiedFaceAssets", faceDetectionService.getUnidentifiedFaceAssetIds());
        model.addAttribute("notice", assets.isEmpty()
                ? "No unidentified faces found."
                : assets.size() + " image(s) contain unidentified faces. Click a thumbnail to identify them.");
        return "fragments/asset-grid :: grid";
    }

    /**
     * Preview fragment enriched with face-detection data for the given asset.
     */
    @GetMapping("/faces/asset/{assetId}")
    public String facePreview(@PathVariable String assetId, Model model) {
        var asset = assetService.find(assetId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        model.addAttribute("asset", asset);
        model.addAttribute("meta", assetService.findMetadata(assetId).orElse(null));
        model.addAttribute("knownPersons", knownFaces.summaries());
        model.addAttribute("faceDetections", faceDetectionService.getDetectionsForAsset(assetId));
        return "fragments/preview :: preview";
    }

    /**
     * Enroll a detected face with a person name, then reload the preview.
     */
    @PostMapping("/faces/detections/{detectionId}/identify")
    public String identifyFace(@PathVariable String detectionId,
                               @RequestParam String displayName,
                               @RequestParam String assetId,
                               Model model) {
        faceDetectionService.enrollFace(detectionId, displayName);

        var asset = assetService.find(assetId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        model.addAttribute("asset", asset);
        model.addAttribute("meta", assetService.findMetadata(assetId).orElse(null));
        model.addAttribute("knownPersons", knownFaces.summaries());
        model.addAttribute("faceDetections", faceDetectionService.getDetectionsForAsset(assetId));
        model.addAttribute("notice", "Face labelled as '" + displayName.trim() + "'.");
        return "fragments/preview :: preview";
    }

    /**
     * Serves a face-crop JPEG from the {@code face-crops-dir}.
     * Path is retrieved from the {@code face_detections} table to prevent
     * arbitrary file access.
     */
    @GetMapping("/faces/crops/{detectionId}")
    public ResponseEntity<Resource> crop(@PathVariable String detectionId) throws IOException {
        var fd = faceDetectionRepo.findById(detectionId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        if (fd.getCropPath() == null) {
            throw new ResponseStatusException(NOT_FOUND, "No crop available");
        }
        Path cropPath = Path.of(fd.getCropPath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(cropPath)) {
            throw new ResponseStatusException(NOT_FOUND, "Crop file not found");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(new FileSystemResource(cropPath));
    }
}
