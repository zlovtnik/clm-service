package com.gprintex.clm.domain;

/**
 * Contract status enum matching Oracle contract_pkg constants.
 */
public enum ContractStatus {
    DRAFT,
    PENDING,
    ACTIVE,
    SUSPENDED,
    CANCELLED,
    COMPLETED;

    /**
     * Check if transition to target status is valid.
     */
    public boolean canTransitionTo(ContractStatus target) {
        return switch (this) {
            case DRAFT -> target == PENDING || target == CANCELLED;
            case PENDING -> target == ACTIVE || target == CANCELLED || target == DRAFT;
            case ACTIVE -> target == SUSPENDED || target == CANCELLED || target == COMPLETED;
            case SUSPENDED -> target == ACTIVE || target == CANCELLED;
            case CANCELLED, COMPLETED -> false;
        };
    }
}
