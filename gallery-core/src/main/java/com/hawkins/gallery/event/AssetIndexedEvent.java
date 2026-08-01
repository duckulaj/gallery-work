package com.hawkins.gallery.event;

/** Published after an asset and its initial metadata have been committed for processing. */
public record AssetIndexedEvent(String assetId) { }
