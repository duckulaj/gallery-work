package com.hawkins.gallery.service;

/** Optional application capability used to run NSFW analysis during AI enrichment. */
public interface NsfwDetectionService {
    void analyseAsset(String assetId);

    default boolean hasResult(String assetId) {
        return false;
    }

    default void queueAsset(String assetId) {
        analyseAsset(assetId);
    }
}
