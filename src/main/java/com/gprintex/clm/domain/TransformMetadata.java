package com.gprintex.clm.domain;

import java.time.LocalDateTime;

/**
 * Transform metadata matching Oracle transform_metadata_t type.
 */
public record TransformMetadata(
    String sourceSystem,
    LocalDateTime transformTimestamp,
    String transformVersion,
    long recordCount,
    long successCount,
    long errorCount
) {
    public static TransformMetadata create(String sourceSystem) {
        return new TransformMetadata(
            sourceSystem,
            LocalDateTime.now(),
            "1.0",
            0, 0, 0
        );
    }

    public TransformMetadata withCounts(long records, long success, long errors) {
        return new TransformMetadata(
            sourceSystem, transformTimestamp, transformVersion,
            records, success, errors
        );
    }

    public boolean hasErrors() {
        return errorCount > 0;
    }

    public double successRate() {
        if (recordCount <= 0) return 0;
        // Clamp successCount to valid range and calculate percentage
        long effectiveSuccess = Math.max(0, Math.min(successCount, recordCount));
        return (double) effectiveSuccess / recordCount * 100;
    }
}
