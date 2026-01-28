package com.gprintex.clm.domain;

/**
 * Validation result matching Oracle validation_result_t type.
 */
public record ValidationResult(
    boolean valid,
    String errorCode,
    String errorMessage,
    String fieldName
) {
    public static ValidationResult success() {
        return new ValidationResult(true, null, null, null);
    }

    public static ValidationResult error(String code, String message, String field) {
        return new ValidationResult(false, code, message, field);
    }

    public static ValidationResult error(String code, String message) {
        return new ValidationResult(false, code, message, null);
    }
}
