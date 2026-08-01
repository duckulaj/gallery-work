package com.hawkins.gallery.review.service;

import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.hawkins.gallery.review.domain.*;
import com.hawkins.gallery.review.repository.*;

@Service
public class ReviewQueueService {
    private final ProcessingJobRepository jobs;

    public ReviewQueueService(ProcessingJobRepository jobs) { this.jobs = jobs; }

    @Transactional
    public void enqueue(String assetId, JobType type, int priority, boolean force) {
        ProcessingJob job = jobs.findByAssetIdAndJobType(assetId, type).orElseGet(ProcessingJob::new);
        if (!force && job.getId() != null && job.getStatus() == JobStatus.COMPLETED) return;
        job.setAssetId(assetId);
        job.setJobType(type);
        job.setPriority(priority);
        job.setStatus(JobStatus.PENDING);
        job.setAttempts(0);
        job.setLastError(null);
        job.setAvailableAt(Instant.now());
        job.setStartedAt(null);
        job.setCompletedAt(null);
        jobs.save(job);
    }

    @Transactional
    public Optional<ProcessingJob> claimNext(JobType type) {
        List<ProcessingJob> found = jobs.next(type, Instant.now(), PageRequest.of(0, 1));
        if (found.isEmpty()) return Optional.empty();
        ProcessingJob job = found.getFirst();
        job.setStatus(JobStatus.RUNNING);
        job.setAttempts(job.getAttempts() + 1);
        job.setStartedAt(Instant.now());
        return Optional.of(jobs.save(job));
    }

    @Transactional
    public void complete(UUID id) {
        jobs.findById(id).ifPresent(j -> {
            j.setStatus(JobStatus.COMPLETED); j.setCompletedAt(Instant.now()); j.setLastError(null); jobs.save(j);
        });
    }

    @Transactional
    public void fail(UUID id, Throwable error) {
        jobs.findById(id).ifPresent(j -> {
            j.setLastError(trim(error.getMessage()));
            if (j.getAttempts() >= j.getMaxAttempts()) {
                j.setStatus(JobStatus.FAILED);
            } else {
                j.setStatus(JobStatus.PENDING);
                j.setAvailableAt(Instant.now().plusSeconds((long) Math.pow(2, j.getAttempts()) * 15));
            }
            jobs.save(j);
        });
    }

    public QueueStats stats() {
        return new QueueStats(jobs.countByStatus(JobStatus.PENDING), jobs.countByStatus(JobStatus.RUNNING),
                jobs.countByStatus(JobStatus.COMPLETED), jobs.countByStatus(JobStatus.FAILED));
    }

    private String trim(String value) {
        if (value == null || value.isBlank()) return "Unknown processing error";
        return value.length() <= 1800 ? value : value.substring(0, 1800);
    }

    public record QueueStats(long pending, long running, long completed, long failed) { }
}
