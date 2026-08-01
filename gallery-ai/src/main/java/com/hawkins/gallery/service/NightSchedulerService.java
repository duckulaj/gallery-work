package com.hawkins.gallery.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Scheduled service that drives overnight AI processing.
 *
 * <p>Window: 02:00–06:00 (server local time).
 *
 * <ul>
 *   <li>At 02:00 – queues all assets that are missing AI metadata and re-queues
 *       all assets so the latest known-face labels can be applied.</li>
 *   <li>At 06:00 – cancels any remaining pending/in-flight jobs so they are
 *       eligible for re-queuing on the next scheduled run.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NightSchedulerService {

    private final AssetService assets;
    private final AiEnrichmentService aiEnrichment;

    /**
     * Triggered at 02:00 — opens the night processing window.
     * Runs "Queue Missing AI" (new/failed images) then "Apply Known Faces"
     * (re-queues completed images with current face-label knowledge).
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void startNightProcessing() {
        log.info("Night AI window opening at 02:00");

        int missing = assets.queueMissingAiGlobal();
        log.info("Night scheduler: queued {} asset(s) missing AI metadata", missing);

        int faces = assets.applyKnownFacesGlobal();
        log.info("Night scheduler: queued {} asset(s) for known-face application", faces);

        aiEnrichment.activateQueue();
    }

    /**
     * Triggered at 06:00 — closes the night processing window.
     * Cancels remaining pending and in-flight jobs so they are re-queued
     * (as CANCELLED) and eligible for the next scheduled run.
     */
    @Scheduled(cron = "0 0 6 * * *")
    public void endNightProcessing() {
        log.info("Night AI window closing at 06:00 — cancelling remaining jobs");

        int halted = assets.haltAiProcessing();
        int cancelled = aiEnrichment.cancelAllInFlight();
        aiEnrichment.deactivateQueue();

        log.info("Night scheduler: halted {} queued job(s), cancelled {} in-flight task(s)", halted, cancelled);
    }
}
