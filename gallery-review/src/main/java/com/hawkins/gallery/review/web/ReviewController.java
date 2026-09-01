package com.hawkins.gallery.review.web;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import com.hawkins.gallery.review.domain.ReviewStatus;
import com.hawkins.gallery.review.service.*;

@Controller
@RequestMapping("/review")
@Validated
public class ReviewController {
    private final ReviewService review;
    private final ReviewQueueService queue;

    public ReviewController(ReviewService review, ReviewQueueService queue) { this.review = review; this.queue = queue; }

    @GetMapping
    public String page(@RequestParam(defaultValue="Flagged") String filter,
                       @RequestParam(defaultValue="0.65") @DecimalMin("0") @DecimalMax("1") double threshold,
                       @RequestParam(defaultValue="") String q, Model model) {
        populate(model, filter, threshold, q);
        return "review/index";
    }

    @GetMapping("/grid")
    public String grid(@RequestParam(defaultValue="Flagged") String filter,
                       @RequestParam(defaultValue="0.65") @DecimalMin("0") @DecimalMax("1") double threshold,
                       @RequestParam(defaultValue="") String q, Model model) {
        populate(model, filter, threshold, q);
        return "review/index :: workspace";
    }

    @GetMapping("/stats") @ResponseBody
    public Stats stats(@RequestParam(defaultValue="0.65") @DecimalMin("0") @DecimalMax("1") double threshold) {
        return new Stats(review.counts(threshold), queue.stats());
    }

    @PostMapping("/status") @ResponseBody
    public ResponseEntity<?> status(@Valid @RequestBody Selection request) {
        return ResponseEntity.ok(java.util.Map.of("changed", review.setStatus(request.ids(), request.status())));
    }

    @PostMapping("/queue") @ResponseBody
    public ResponseEntity<?> queue(@Valid @RequestBody QueueRequest request) {
        return ResponseEntity.ok(java.util.Map.of("queued", review.queueNsfw(request.ids(), request.force())));
    }

    @PostMapping("/quarantine") @ResponseBody
    public ResponseEntity<?> quarantine(@Valid @RequestBody Ids request) throws Exception {
        return ResponseEntity.ok(java.util.Map.of("changed", review.quarantine(request.ids())));
    }

    @PostMapping("/restore") @ResponseBody
    public ResponseEntity<?> restore(@Valid @RequestBody Ids request) throws Exception {
        return ResponseEntity.ok(java.util.Map.of("changed", review.restore(request.ids())));
    }

    private void populate(Model model, String filter, double threshold, String q) {
        model.addAttribute("cards", review.cards(filter, threshold, q));
        model.addAttribute("counts", review.counts(threshold));
        model.addAttribute("queue", queue.stats());
        model.addAttribute("filter", filter); model.addAttribute("threshold", threshold); model.addAttribute("q", q);
    }

    public record Ids(@NotEmpty List<@NotBlank String> ids) { }
    public record Selection(@NotEmpty List<@NotBlank String> ids, ReviewStatus status) { }
    public record QueueRequest(List<@NotBlank String> ids, boolean force) { }
    public record Stats(ReviewService.Counts counts, ReviewQueueService.QueueStats queue) { }
}
