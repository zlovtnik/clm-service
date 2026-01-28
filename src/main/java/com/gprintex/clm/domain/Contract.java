package com.gprintex.clm.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Contract domain record - immutable value object matching Oracle contract_t type.
 * Functional style with Optional for nullable fields.
 */
public record Contract(
    Optional<Long> id,
    String tenantId,
    String contractNumber,
    Optional<String> contractType,
    Long customerId,
    LocalDate startDate,
    Optional<LocalDate> endDate,
    Optional<Integer> durationMonths,
    boolean autoRenew,
    Optional<BigDecimal> totalValue,
    Optional<String> paymentTerms,
    Optional<String> billingCycle,
    String status,
    Optional<LocalDateTime> signedAt,
    Optional<String> signedBy,
    Optional<String> notes,
    Optional<LocalDateTime> createdAt,
    Optional<LocalDateTime> updatedAt,
    Optional<String> createdBy,
    Optional<String> updatedBy
) {
    // Compact constructor for validation
    public Contract {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (contractNumber == null || contractNumber.isBlank()) {
            throw new IllegalArgumentException("contractNumber is required");
        }
        if (customerId == null) {
            throw new IllegalArgumentException("customerId is required");
        }
        if (startDate == null) {
            throw new IllegalArgumentException("startDate is required");
        }
        if (status == null) {
            status = ContractStatus.DRAFT.name();
        }
        // Normalize Optional fields to prevent NPE on subsequent operations
        id = id != null ? id : Optional.empty();
        contractType = contractType != null ? contractType : Optional.empty();
        endDate = endDate != null ? endDate : Optional.empty();
        durationMonths = durationMonths != null ? durationMonths : Optional.empty();
        totalValue = totalValue != null ? totalValue : Optional.empty();
        paymentTerms = paymentTerms != null ? paymentTerms : Optional.empty();
        billingCycle = billingCycle != null ? billingCycle : Optional.empty();
        signedAt = signedAt != null ? signedAt : Optional.empty();
        signedBy = signedBy != null ? signedBy : Optional.empty();
        notes = notes != null ? notes : Optional.empty();
        createdAt = createdAt != null ? createdAt : Optional.empty();
        updatedAt = updatedAt != null ? updatedAt : Optional.empty();
        createdBy = createdBy != null ? createdBy : Optional.empty();
        updatedBy = updatedBy != null ? updatedBy : Optional.empty();
    }

    /**
     * Factory method for creating a new draft contract.
     */
    public static Contract draft(String tenantId, String contractNumber, Long customerId, LocalDate startDate) {
        return new Contract(
            Optional.empty(),
            tenantId,
            contractNumber,
            Optional.empty(),
            customerId,
            startDate,
            Optional.empty(),
            Optional.empty(),
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            ContractStatus.DRAFT.name(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }

    /**
     * Create a copy with updated status.
     * Validates newStatus against allowed ContractStatus values.
     */
    public Contract withStatus(String newStatus) {
        return withStatus(newStatus, this.updatedBy.orElse(null));
    }

    /**
     * Create a copy with updated status and updatedBy actor.
     * Validates newStatus against allowed ContractStatus values.
     */
    public Contract withStatus(String newStatus, String actor) {
        // Validate status against allowed values
        try {
            ContractStatus.valueOf(newStatus);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid contract status: " + newStatus);
        }
        return new Contract(
            id, tenantId, contractNumber, contractType, customerId, startDate, endDate,
            durationMonths, autoRenew, totalValue, paymentTerms, billingCycle,
            newStatus, signedAt, signedBy, notes, createdAt, 
            Optional.of(LocalDateTime.now()), createdBy, Optional.ofNullable(actor)
        );
    }

    /**
     * Create a copy with ID (after insert).
     */
    public Contract withId(Long newId) {
        return new Contract(
            Optional.ofNullable(newId), tenantId, contractNumber, contractType, customerId, startDate, endDate,
            durationMonths, autoRenew, totalValue, paymentTerms, billingCycle,
            status, signedAt, signedBy, notes, createdAt, updatedAt, createdBy, updatedBy
        );
    }

    /**
     * Create a copy with a different tenantId (preserves all other fields).
     */
    public Contract withTenantId(String newTenantId) {
        if (newTenantId == null || newTenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        return new Contract(
            id, newTenantId, contractNumber, contractType, customerId, startDate, endDate,
            durationMonths, autoRenew, totalValue, paymentTerms, billingCycle,
            status, signedAt, signedBy, notes, createdAt, updatedAt, createdBy, updatedBy
        );
    }

    /**
     * Check if contract is active.
     */
    public boolean isActive() {
        return ContractStatus.ACTIVE.name().equals(status);
    }

    /**
     * Check if contract can be modified.
     */
    public boolean isModifiable() {
        return ContractStatus.DRAFT.name().equals(status) 
            || ContractStatus.PENDING.name().equals(status);
    }
}
