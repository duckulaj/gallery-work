package com.hawkins.gallery.review.service;

import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.hawkins.gallery.review.domain.*;
import com.hawkins.gallery.review.repository.*;

@Service
public class ReviewQueueService {
    private final ProcessingJobRepository jobs;
    private final String workerId = UUID.randomUUID().toString();
    private final long leaseSeconds;

    public ReviewQueueService(ProcessingJobRepository jobs,
            @Value("${app.jobs.lease-seconds:300}") long leaseSeconds) {
        this.jobs = jobs;
        this.leaseSeconds = Math.max(30, leaseSeconds);
    }

    @Transactional
    public void enqueue(String assetId, JobType type, int priority, boolean force) {
        ProcessingJob job = jobs.findByAssetIdAndJobType(assetId, type).orElseGet(ProcessingJob::new);
        if (job.getId() != null && job.getStatus() == JobStatus.RUNNING) return;
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
        job.setLeaseUntil(null);
        job.setWorkerId(null);
        jobs.save(job);
    }

    @Transactional
    public Optional<ProcessingJob> claimNext(JobType type) {
        Instant now = Instant.now();
        Optional<ProcessingJob> found = jobs.lockNext(type.name(), now);
        if (found.isEmpty()) return Optional.empty();
        ProcessingJob job = found.get();
        job.setStatus(JobStatus.RUNNING);
        job.setAttempts(job.getAttempts() + 1);
        job.setStartedAt(now);
        job.setWorkerId(workerId);
        job.setLeaseUntil(now.plusSeconds(leaseSeconds));
        return Optional.of(jobs.save(job));
    }

    @Transactional
    public List<ProcessingJob> claimNextBatch(JobType type, int limit) {
        int safeLimit = Math.max(1, limit);
        List<ProcessingJob> claimed = new ArrayList<>(safeLimit);
        Instant now = Instant.now();
        for (int i = 0; i < safeLimit; i++) {
            Optional<ProcessingJob> found = jobs.lockNext(type.name(), now);
            if (found.isEmpty()) {
                break;
            }
            ProcessingJob job = found.get();
            job.setStatus(JobStatus.RUNNING);
            job.setAttempts(job.getAttempts() + 1);
            job.setStartedAt(now);
            job.setWorkerId(workerId);
            job.setLeaseUntil(now.plusSeconds(leaseSeconds));
            claimed.add(jobs.save(job));
        }
        return claimed;
    }

    @Transactional
    public void complete(UUID id, String owner) {
        if (jobs.completeOwned(id, owner, JobStatus.COMPLETED, Instant.now()) != 1) {
            throw new IllegalStateException("Job lease is no longer owned by this worker: " + id);
        }
    }

    @Transactional
    public void fail(UUID id, String owner, Throwable error) {
        jobs.findById(id).ifPresent(j -> {
            if (j.getStatus() != JobStatus.RUNNING || !Objects.equals(owner, j.getWorkerId())) return;
            j.setLastError(trim(error.getMessage()));
            j.setWorkerId(null);
            j.setLeaseUntil(null);
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

    @Transactional
    public int enqueueNsfw(Collection<String> assetIds, boolean force) {
        boolean all = assetIds == null || assetIds.isEmpty();
        Collection<String> safeIds = all ? List.of("__all__") : assetIds;
        return jobs.enqueueNsfw(safeIds, all, force);
    }

    @org.springframework.scheduling.annotation.Scheduled(cron = "${app.jobs.cleanup-cron:0 30 3 * * *}")
    @Transactional
    public void purgeCompletedHistory() {
        jobs.deleteCompletedBefore(Instant.now().minus(java.time.Duration.ofDays(30)));
    }

    private String trim(String value) {
        if (value == null || value.isBlank()) return "Unknown processing error";
        return value.length() <= 1800 ? value : value.substring(0, 1800);
    }

    public record QueueStats(long pending, long running, long completed, long failed) { }
}
