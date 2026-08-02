package com.hawkins.gallery.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

import com.hawkins.gallery.domain.Folder;
import com.hawkins.gallery.repository.AssetRepository;
import com.hawkins.gallery.repository.FolderRepository;
import com.hawkins.gallery.service.AiEnrichmentService;
import com.hawkins.gallery.service.AlbumService;
import com.hawkins.gallery.service.AssetService;
import com.hawkins.gallery.service.FaceDetectionService;

import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class PageController {
    private final AssetService assetService;
    private final FolderRepository folders;
    private final AssetRepository assetRepo;
    private final AlbumService albumService;
    private final AiEnrichmentService aiEnrichment;
    private final FaceDetectionService faceDetectionService;

    @GetMapping("/")
    public String index(Model model) {
        Folder root = assetService.root();
        populateWorkspace(model, root);
        return "index";
    }

    @GetMapping("/imports")
    public String imports(Model model) {
        model.addAttribute("initialPanel", "imports");
        return index(model);
    }

    @GetMapping("/processing")
    public String processing(Model model) {
        model.addAttribute("initialPanel", "processing");
        return index(model);
    }

    @GetMapping("/folders/{id}")
    public String folder(@PathVariable String id,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest,
            Model model) {
        Folder folder = folders.findById(id).orElseGet(assetService::root);
        populateWorkspace(model, folder);
        return hxRequest != null ? "fragments/workspace :: workspace" : "index";
    }

    private void populateWorkspace(Model model, Folder folder) {
        model.addAttribute("folder", folder);
        model.addAttribute("folders", folders.findAll());
        model.addAttribute("albumTree", albumService.buildTree(folder.getId()));
        model.addAttribute("assets", assetRepo.findByFolderIdOrderByCreatedAtDesc(folder.getId()));
        model.addAttribute("aiStats", aiEnrichment.stats());
        model.addAttribute("unidentifiedFaceAssets", faceDetectionService.getUnidentifiedFaceAssetIds());
        model.addAttribute("unidentifiedFaceCount", faceDetectionService.getUnidentifiedFaceAssetIds().size());
    }
}
