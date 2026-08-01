package com.hawkins.gallery.domain;

public enum AiStatus {
    PENDING,
    RETRY,
    PROCESSING,
    COMPLETE,
    FAILED,
    CANCELLED;

    /** Convenience check for states that are considered "not yet complete". */
    public boolean isActive() {
        return this == PENDING || this == RETRY || this == PROCESSING;
    }
}
