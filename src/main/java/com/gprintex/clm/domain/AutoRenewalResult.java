package com.gprintex.clm.domain;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Result of auto-renewal processing.
 */
public record AutoRenewalResult(
    int renewedCount,
    List<String> errors
) {
    /**
     * Canonical constructor with null-safety for errors list.
     */
    public AutoRenewalResult {
        errors = Objects.requireNonNullElse(errors, Collections.emptyList());
    }

    public static AutoRenewalResult success(int count) {
        return new AutoRenewalResult(count, List.of());
    }

    public static AutoRenewalResult withErrors(int count, List<String> errors) {
        return new AutoRenewalResult(count, Objects.requireNonNullElse(errors, Collections.emptyList()));
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}
