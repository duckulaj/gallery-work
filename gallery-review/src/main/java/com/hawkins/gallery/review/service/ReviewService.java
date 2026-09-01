package com.hawkins.gallery.review.service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.PageRequest;
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
    private final TransactionTemplate transactions;

    @Autowired
    public ReviewService(AssetRepository assets, AssetReviewRepository reviews, ReviewQueueService queue,
                         @Value("${app.review.quarantine-root:./data/gallery/quarantine}") String quarantineRoot,
                         PlatformTransactionManager transactionManager) {
        this.assets = assets; this.reviews = reviews; this.queue = queue;
        this.quarantineRoot = Path.of(quarantineRoot).toAbsolutePath().normalize();
        this.transactions = new TransactionTemplate(transactionManager);
    }

    ReviewService(AssetRepository assets, AssetReviewRepository reviews, ReviewQueueService queue,
                  String quarantineRoot) {
        this.assets = assets; this.reviews = reviews; this.queue = queue;
        this.quarantineRoot = Path.of(quarantineRoot).toAbsolutePath().normalize();
        this.transactions = null;
    }

    @Transactional(readOnly = true)
    public List<ReviewCard> cards(String filter, double threshold, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String normalFilter = switch (filter == null ? "Flagged" : filter) {
            case "All" -> "ALL";
            case "Flagged" -> "FLAGGED";
            case "Pending" -> "PENDING";
            case "Kept" -> "KEPT";
            case "Quarantined" -> "QUARANTINED";
            case "Errors" -> "ERROR";
            default -> "ALL";
        };
        return reviews.findCards(normalFilter, threshold, q, PageRequest.of(0, 1000)).stream()
                .map(ReviewCard::of).toList();
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

    public int quarantine(Collection<String> ids) throws IOException {
        Files.createDirectories(quarantineRoot);
        int changed = 0;
        for (String id : ids) {
            Asset asset = assets.findById(id).orElseThrow();
            Path source = Path.of(asset.getStoragePath()).toAbsolutePath().normalize();
            Path target = uniqueTarget(source.getFileName());
            update(id, () -> {
                AssetReview review = reviews.findById(id).orElseGet(AssetReview::new);
                review.setAssetId(id); review.setOriginalPath(source.toString()); review.setQuarantinePath(target.toString());
                review.setOperationStatus(FileOperationStatus.MOVE_PENDING); review.setOperationError(null);
                reviews.save(review);
            });
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(source, target);
            } catch (IOException ex) {
                recordOperationFailure(id, FileOperationStatus.MOVE_FAILED, ex);
                throw ex;
            }
            update(id, () -> {
                AssetReview review = reviews.findById(id).orElseThrow();
                Asset current = assets.findById(id).orElseThrow();
                current.setStoragePath(target.toString());
                review.setReviewStatus(ReviewStatus.QUARANTINED); review.setManualOverride(true);
                review.setReviewedAt(Instant.now()); review.setOperationStatus(FileOperationStatus.MOVED);
                assets.save(current); reviews.save(review);
            });
            changed++;
        }
        return changed;
    }

    public int restore(Collection<String> ids) throws IOException {
        int changed = 0;
        for (String id : ids) {
            AssetReview review = reviews.findById(id).orElseThrow();
            if (review.getQuarantinePath() == null || review.getOriginalPath() == null) continue;
            Path source = Path.of(review.getQuarantinePath());
            Path target = Path.of(review.getOriginalPath());
            update(id, () -> {
                AssetReview pending = reviews.findById(id).orElseThrow();
                pending.setOperationStatus(FileOperationStatus.RESTORE_PENDING); pending.setOperationError(null);
                reviews.save(pending);
            });
            Files.createDirectories(target.getParent());
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(source, target);
            } catch (IOException ex) {
                recordOperationFailure(id, FileOperationStatus.RESTORE_FAILED, ex);
                throw ex;
            }
            update(id, () -> {
                AssetReview restored = reviews.findById(id).orElseThrow();
                Asset current = assets.findById(id).orElseThrow();
                current.setStoragePath(target.toString());
                restored.setReviewStatus(ReviewStatus.RESTORED); restored.setQuarantinePath(null);
                restored.setOperationStatus(FileOperationStatus.NONE); restored.setManualOverride(true);
                restored.setReviewedAt(Instant.now()); assets.save(current); reviews.save(restored);
            });
            changed++;
        }
        return changed;
    }

    /**
     * Repairs database state after a crash between a completed file move and its
     * following transaction. It never moves files; ambiguous states are retained
     * as failures for an operator to inspect and retry.
     */
    @Scheduled(fixedDelayString = "${app.review.file-reconciliation-ms:60000}")
    public void reconcilePendingFileOperations() {
        if (transactions == null) return;
        List<AssetReview> pending = reviews.findByOperationStatusIn(List.of(
                FileOperationStatus.MOVE_PENDING, FileOperationStatus.RESTORE_PENDING));
        for (AssetReview review : pending) {
            reconcile(review.getAssetId());
        }
    }

    private void reconcile(String id) {
        update(id, () -> {
            AssetReview review = reviews.findById(id).orElse(null);
            if (review == null || review.getOriginalPath() == null) return;
            Path original = Path.of(review.getOriginalPath());
            Path quarantined = review.getQuarantinePath() == null ? null : Path.of(review.getQuarantinePath());
            boolean originalExists = Files.exists(original);
            boolean quarantinedExists = quarantined != null && Files.exists(quarantined);

            if (review.getOperationStatus() == FileOperationStatus.MOVE_PENDING) {
                if (!originalExists && quarantinedExists) {
                    Asset asset = assets.findById(id).orElseThrow();
                    asset.setStoragePath(quarantined.toString());
                    review.setReviewStatus(ReviewStatus.QUARANTINED);
                    review.setManualOverride(true);
                    review.setReviewedAt(Instant.now());
                    review.setOperationStatus(FileOperationStatus.MOVED);
                    review.setOperationError(null);
                    assets.save(asset);
                } else {
                    review.setOperationStatus(FileOperationStatus.MOVE_FAILED);
                    review.setOperationError(fileStateMessage(originalExists, quarantinedExists));
                }
                reviews.save(review);
            } else if (review.getOperationStatus() == FileOperationStatus.RESTORE_PENDING) {
                if (originalExists && !quarantinedExists) {
                    Asset asset = assets.findById(id).orElseThrow();
                    asset.setStoragePath(original.toString());
                    review.setReviewStatus(ReviewStatus.RESTORED);
                    review.setQuarantinePath(null);
                    review.setOperationStatus(FileOperationStatus.NONE);
                    review.setOperationError(null);
                    review.setManualOverride(true);
                    review.setReviewedAt(Instant.now());
                    assets.save(asset);
                } else {
                    review.setOperationStatus(FileOperationStatus.RESTORE_FAILED);
                    review.setOperationError(fileStateMessage(originalExists, quarantinedExists));
                }
                reviews.save(review);
            }
        });
    }

    private static String fileStateMessage(boolean originalExists, boolean quarantinedExists) {
        return "File operation was interrupted; originalExists=" + originalExists
                + ", quarantineExists=" + quarantinedExists;
    }

    public Counts counts(double threshold) {
        long total = assets.count();
        long reviewed = reviews.count();
        return new Counts(total, reviews.countFlagged(threshold), reviews.countByReviewStatus(ReviewStatus.KEPT),
                reviews.countByReviewStatus(ReviewStatus.QUARANTINED),
                total - reviewed + reviews.countByReviewStatus(ReviewStatus.PENDING),
                reviews.countByReviewStatus(ReviewStatus.ERROR));
    }

    private Path uniqueTarget(Path filename) throws IOException {
        Path target = quarantineRoot.resolve(filename);
        if (!Files.exists(target)) return target;
        String name = filename.toString(); int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot); String ext = dot < 0 ? "" : name.substring(dot);
        return quarantineRoot.resolve(stem + "_" + System.nanoTime() + ext);
    }

    private void recordOperationFailure(String id, FileOperationStatus status, Exception error) {
        update(id, () -> reviews.findById(id).ifPresent(review -> {
            review.setOperationStatus(status);
            review.setOperationError(error.getMessage());
            reviews.save(review);
        }));
    }

    private void update(String id, Runnable work) {
        if (transactions == null) work.run();
        else transactions.executeWithoutResult(status -> work.run());
    }

    public record ReviewCard(String id, String filename, String path, String thumbnailPath, Double score,
                             String level, String status, boolean overridden, String error) {
        static ReviewCard of(Asset a, AssetReview r) {
            return new ReviewCard(a.getId(), a.getFilename(), a.getStoragePath(), a.getThumbnailPath(),
                    r == null ? null : r.getNsfwScore(), r == null ? "UNKNOWN" : r.getNsfwLevel().name(),
                    r == null ? "PENDING" : r.getReviewStatus().name(), r != null && r.isManualOverride(),
                    r == null ? null : r.getErrorMessage());
        }
        static ReviewCard of(AssetReviewRepository.ReviewCardRow row) {
            return new ReviewCard(row.getId(), row.getFilename(), row.getPath(), row.getThumbnailPath(),
                    row.getScore(), row.getLevel(), row.getStatus(), row.getOverridden(), row.getError());
        }
    }
    public record Counts(long total, long flagged, long kept, long quarantined, long pending, long errors) { }
}
