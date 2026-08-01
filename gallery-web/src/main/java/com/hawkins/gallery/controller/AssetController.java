package com.hawkins.gallery.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import com.hawkins.gallery.repository.AssetMetadataRepository;
import com.hawkins.gallery.repository.AssetRepository;
import com.hawkins.gallery.service.AiEnrichmentService;
import com.hawkins.gallery.service.AssetService;
import com.hawkins.gallery.service.FaceDetectionService;
import com.hawkins.gallery.service.KnownFaceService;
import com.hawkins.gallery.service.SearchService;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Controller
@RequiredArgsConstructor
public class AssetController {
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp",
            "image/bmp", "image/tiff", "image/svg+xml", "image/heic", "image/heif");

    private final SearchService search;
    private final AssetService assets;
    private final AssetRepository assetRepo;
    private final AssetMetadataRepository metas;
    private final AiEnrichmentService aiEnrichment;
    private final KnownFaceService knownFaces;
    private final FaceDetectionService faceDetectionService;

    @GetMapping("/assets")
    public String grid(@RequestParam String folderId, @RequestParam(required = false) String q, Model model) {
        model.addAttribute("assets", search.search(folderId, q));
        return "fragments/asset-grid :: grid";
    }



    @PostMapping("/assets/index-directory")
    public void indexDirectory(@RequestParam String folderId, @RequestParam(required = false) String q,
            @RequestParam String directoryPath,
            @RequestParam(defaultValue = "false") boolean recursive,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest,
            HttpServletResponse response) throws IOException {
        var result = assets.indexDirectory(folderId, directoryPath, recursive);
        String target = "/folders/" + result.rootAlbumId();
        if (hxRequest != null) {
            response.setHeader("HX-Redirect", target);
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        } else {
            response.sendRedirect(target);
        }
    }


    @PostMapping("/assets/reindex-ai")
    public String reindexAi(@RequestParam String folderId, @RequestParam(required = false) String q, Model model) {
        var result = assets.reindexAi(folderId);
        aiEnrichment.activateQueue();
        model.addAttribute("notice", result.message());
        model.addAttribute("assets", search.search(folderId, q));
        model.addAttribute("aiStats", aiEnrichment.stats());
        return "fragments/asset-grid :: grid";
    }

    @PostMapping("/assets/queue-missing-ai")
    public String queueMissingAi(@RequestParam String folderId, @RequestParam(required = false) String q, Model model) {
        var result = assets.queueMissingAi(folderId);
        aiEnrichment.activateQueue();
        model.addAttribute("notice", result.message());
        model.addAttribute("assets", search.search(folderId, q));
        model.addAttribute("aiStats", aiEnrichment.stats());
        return "fragments/asset-grid :: grid";
    }

    @GetMapping("/assets/ai-status")
    public String aiStatus(Model model) {
        model.addAttribute("aiStats", aiEnrichment.stats());
        return "fragments/toolbar :: aiStatus";
    }

    @GetMapping("/assets/ai-panel")
    public String aiPanel(Model model) {
        model.addAttribute("stats", aiEnrichment.stats());
        return "fragments/ai-panel :: panel";
    }

    @PostMapping({"/assets/ai/halt", "/assets/ai-halt"})
    public ResponseEntity<String> haltAiProcessing() {
        int changed = assets.haltAiProcessing();
        int cancelled = 0;
        try {
            cancelled = aiEnrichment.cancelAllInFlight();
            aiEnrichment.deactivateQueue();
        } catch (Exception ex) {
            // best-effort cancellation; log and continue
            // note: aiEnrichment may not be available in some tests
        }
        return ResponseEntity.ok("Halted " + changed + " AI job(s); requested cancellation of " + cancelled + " in-flight tasks");
    }

    @PostMapping("/faces/known")
    public String addKnownFace(@RequestParam String assetId,
            @RequestParam String displayName,
            @RequestParam(required = false) String faceDescription,
            Model model) {
        var result = knownFaces.addExample(assetId, displayName, faceDescription);
        var a = assetRepo.findById(assetId).orElseThrow();
        model.addAttribute("notice", result.message());
        model.addAttribute("asset", a);
        model.addAttribute("meta", metas.findById(assetId).orElse(null));
        model.addAttribute("knownPersons", knownFaces.summaries());
        return "fragments/preview :: preview";
    }

    @PostMapping("/faces/queue-recognition")
    public String queueFaceRecognition(@RequestParam String folderId, @RequestParam(required = false) String q, Model model) {
        var result = assets.reindexAi(folderId);
        aiEnrichment.activateQueue();
        model.addAttribute("notice", "Known faces updated. " + result.message());
        model.addAttribute("assets", search.search(folderId, q));
        model.addAttribute("aiStats", aiEnrichment.stats());
        return "fragments/asset-grid :: grid";
    }

    @GetMapping("/assets/{id}/preview")
    public String preview(@PathVariable String id, Model model) {
        var a = assetRepo.findById(id).orElseThrow();
        model.addAttribute("asset", a);
        model.addAttribute("meta", metas.findById(id).orElse(null));
        model.addAttribute("knownPersons", knownFaces.summaries());
        model.addAttribute("faceDetections", faceDetectionService.getDetectionsForAsset(id));
        return "fragments/preview :: preview";
    }

    @PostMapping("/assets/delete")
    public String delete(@RequestParam String folderId, @RequestParam(required = false) String q,
            @RequestParam(required = false) List<String> ids, Model model) {
        if (ids != null && !ids.isEmpty()) {
            assets.delete(ids);
        }
        model.addAttribute("assets", search.search(folderId, q));
        return "fragments/asset-grid :: grid";
    }

    @GetMapping("/thumbs/{id}")
    public ResponseEntity<Resource> thumb(@PathVariable String id) throws Exception {
        var a = assetRepo.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        Path p = resolveAssetPath(Optional.ofNullable(a.getThumbnailPath()).orElse(a.getStoragePath()));
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(new FileSystemResource(p));
    }

    @GetMapping("/files/{id}")
    public ResponseEntity<Resource> file(@PathVariable String id) throws Exception {
        var a = assetRepo.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        Path p = resolveAssetPath(a.getStoragePath());
        MediaType mediaType = safeMediaType(a.getContentType());
        return ResponseEntity.ok().contentType(mediaType).body(new FileSystemResource(p));
    }

    /**
     * Resolves a stored asset path and verifies it exists and is a regular file.
     * Accepts both paths under storageRoot (thumbnails) and under user.home (source images).
     */
    private Path resolveAssetPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new ResponseStatusException(NOT_FOUND, "Asset path not set");
        }
        Path p = Path.of(rawPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(p)) {
            throw new ResponseStatusException(NOT_FOUND, "Asset file not found");
        }
        return p;
    }

    /** Returns the content type only if it is an allowed image MIME type; falls back to octet-stream. */
    private static MediaType safeMediaType(String contentType) {
        if (contentType != null) {
            try {
                MediaType parsed = MediaType.parseMediaType(contentType);
                String key = parsed.getType() + "/" + parsed.getSubtype();
                if (ALLOWED_MIME_TYPES.contains(key)) {
                    return parsed;
                }
            } catch (Exception ignored) {
            }
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
