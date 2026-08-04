package com.hawkins.gallery.review.service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.hawkins.gallery.domain.Asset;
import com.hawkins.gallery.repository.AssetRepository;
import com.hawkins.gallery.review.domain.*;
import com.hawkins.gallery.review.repository.AssetReviewRepository;

@Service
@SuppressWarnings("null")
public class ReviewService {
    private final AssetRepository assets;
    private final AssetReviewRepository reviews;
    private final ReviewQueueService queue;
    private final Path quarantineRoot;

    public ReviewService(AssetRepository assets, AssetReviewRepository reviews, ReviewQueueService queue,
                         @Value("${app.review.quarantine-root:./data/gallery/quarantine}") String quarantineRoot) {
        this.assets = assets; this.reviews = reviews; this.queue = queue;
        this.quarantineRoot = Path.of(quarantineRoot).toAbsolutePath().normalize();
    }

    @Transactional(readOnly = true)
    public List<ReviewCard> cards(String filter, double threshold, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return assets.findAll().stream().map(asset -> {
            AssetReview r = reviews.findById(asset.getId()).orElse(null);
            return ReviewCard.of(asset, r);
        }).filter(c -> matches(c, filter, threshold, q))
          .sorted(Comparator.comparing(ReviewCard::score, Comparator.nullsLast(Comparator.reverseOrder())))
          .limit(1000).toList();
    }

    @Transactional
    public int setStatus(Collection<String> ids, ReviewStatus status) {
        int changed = 0;
        for (String id : ids) {
            AssetReview review = reviews.findById(id).orElseGet(AssetReview::new);
            review.setAssetId(id);
            review.setReviewStatus(status);
            review.setManualOverride(true);
            review.setReviewedAt(Instant.now());
            reviews.save(review);
            changed++;
        }
        return changed;
    }

    @Transactional
    public int queueNsfw(Collection<String> ids, boolean force) {
        Collection<String> targets = (ids == null || ids.isEmpty())
                ? assets.findAll().stream().map(Asset::getId).toList()
                : ids;
        targets.forEach(id -> queue.enqueue(id, JobType.NSFW, 40, force));
        return targets.size();
    }

    @Transactional
    public int quarantine(Collection<String> ids) throws IOException {
        Files.createDirectories(quarantineRoot);
        int changed = 0;
        for (String id : ids) {
            Asset asset = assets.findById(id).orElseThrow();
            Path source = Path.of(asset.getStoragePath()).toAbsolutePath().normalize();
            Path target = uniqueTarget(source.getFileName());
            Files.move(source, target);
            AssetReview review = reviews.findById(id).orElseGet(AssetReview::new);
            review.setAssetId(id); review.setOriginalPath(source.toString()); review.setQuarantinePath(target.toString());
            review.setReviewStatus(ReviewStatus.QUARANTINED); review.setManualOverride(true); review.setReviewedAt(Instant.now());
            reviews.save(review); changed++;
        }
        return changed;
    }

    @Transactional
    public int restore(Collection<String> ids) throws IOException {
        int changed = 0;
        for (String id : ids) {
            AssetReview review = reviews.findById(id).orElseThrow();
            if (review.getQuarantinePath() == null || review.getOriginalPath() == null) continue;
            Path source = Path.of(review.getQuarantinePath());
            Path target = Path.of(review.getOriginalPath());
            Files.createDirectories(target.getParent());
            Files.move(source, target);
            review.setReviewStatus(ReviewStatus.RESTORED); review.setQuarantinePath(null);
            review.setManualOverride(true); review.setReviewedAt(Instant.now()); reviews.save(review); changed++;
        }
        return changed;
    }

    public Counts counts(double threshold) {
        List<ReviewCard> all = cards("All", threshold, "");
        return new Counts(all.size(), count(all, "FLAGGED"), count(all, "KEPT"), count(all, "QUARANTINED"),
                count(all, "PENDING"), count(all, "ERROR"));
    }

    private long count(List<ReviewCard> cards, String status) { return cards.stream().filter(c -> status.equals(c.status())).count(); }
    private boolean matches(ReviewCard c, String filter, double threshold, String q) {
        boolean text = q.isBlank() || c.filename().toLowerCase(Locale.ROOT).contains(q) || c.path().toLowerCase(Locale.ROOT).contains(q);
        if (!text) return false;
        return switch (filter == null ? "Flagged" : filter) {
            case "All" -> true;
            case "Flagged" -> (c.score() != null && c.score() >= threshold) || "FLAGGED".equals(c.status());
            case "Pending" -> "PENDING".equals(c.status());
            case "Kept" -> "KEPT".equals(c.status());
            case "Quarantined" -> "QUARANTINED".equals(c.status());
            case "Errors" -> "ERROR".equals(c.status());
            default -> true;
        };
    }
    private Path uniqueTarget(Path filename) throws IOException {
        Path target = quarantineRoot.resolve(filename);
        if (!Files.exists(target)) return target;
        String name = filename.toString(); int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot); String ext = dot < 0 ? "" : name.substring(dot);
        return quarantineRoot.resolve(stem + "_" + System.nanoTime() + ext);
    }

    public record ReviewCard(String id, String filename, String path, String thumbnailPath, Double score,
                             String level, String status, boolean overridden, String error) {
        static ReviewCard of(Asset a, AssetReview r) {
            return new ReviewCard(a.getId(), a.getFilename(), a.getStoragePath(), a.getThumbnailPath(),
                    r == null ? null : r.getNsfwScore(), r == null ? "UNKNOWN" : r.getNsfwLevel().name(),
                    r == null ? "PENDING" : r.getReviewStatus().name(), r != null && r.isManualOverride(),
                    r == null ? null : r.getErrorMessage());
        }
    }
    public record Counts(long total, long flagged, long kept, long quarantined, long pending, long errors) { }
}
