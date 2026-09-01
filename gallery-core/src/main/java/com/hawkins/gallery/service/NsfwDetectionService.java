package com.hawkins.gallery.service;

/** Optional application capability used to run NSFW analysis during AI enrichment. */
public interface NsfwDetectionService {
    void analyseAsset(String assetId);
}
