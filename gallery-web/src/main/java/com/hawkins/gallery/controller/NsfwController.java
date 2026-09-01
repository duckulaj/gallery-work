package com.hawkins.gallery.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
@Controller
public class NsfwController {
    @Value("${app.ai.nsfw.review-threshold:0.55}")
    private double defaultThreshold;

    @GetMapping("/review/sensitive")
    public String review(@RequestParam(required = false) Double threshold) {
        double score = threshold == null ? defaultThreshold : threshold;
        return "redirect:/review?threshold=" + score;
    }

}
