package com.gprintex.clm.domain;

/**
 * Message status enum matching Oracle integration_pkg constants.
 */
public enum MessageStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    DEAD_LETTER
}
