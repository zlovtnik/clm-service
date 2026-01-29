package com.gprintex.clm.repository;

import com.gprintex.clm.domain.AutoRenewalResult;
import com.gprintex.clm.domain.Contract;
import com.gprintex.clm.domain.ContractStatistics;
import com.gprintex.clm.domain.TransformMetadata;
import com.gprintex.clm.domain.ValidationResult;
import io.vavr.control.Either;
import io.vavr.control.Try;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Functional repository interface for Contract operations.
 * All methods return functional types (Optional, Either, Try, Stream).
 */
public interface ContractRepository {

    // ========================================================================
    // INSERT OPERATIONS
    // ========================================================================

    /**
     * Insert a single contract.
     * @return Either with validation errors (Left) or the created contract with ID (Right)
     */
    Either<List<ValidationResult>, Contract> insert(Contract contract, String user);

    /**
     * Bulk insert contracts with validation.
     * @return Metadata with counts and any validation errors
     */
    Try<TransformMetadata> bulkInsert(List<Contract> contracts, String user);

    // ========================================================================
    // QUERY OPERATIONS
    // ========================================================================

    /**
     * Find contract by ID.
     */
    Optional<Contract> findById(String tenantId, Long id);

    /**
     * Find contract by contract number.
     */
    Optional<Contract> findByNumber(String tenantId, String contractNumber);

    /**
     * Stream contracts matching filter criteria.
     * Uses pipelined function from Oracle for efficient streaming.
     */
    Stream<Contract> findByFilter(ContractFilter filter);

    /**
     * Count contracts matching criteria.
     */
    long count(String tenantId, String status, Long customerId);

    // ========================================================================
    // UPDATE OPERATIONS
    // ========================================================================

    /**
     * Update contract.
     * @return Either with validation error (Left) or updated contract (Right)
     */
    Either<ValidationResult, Contract> update(Contract contract, String user);

    /**
     * Update contract status with state machine validation.
     * @return Either with validation error (Left) or updated contract (Right)
     */
    Either<ValidationResult, Contract> updateStatus(
        String tenantId, Long id, String newStatus, String user, String reason
    );

    // ========================================================================
    // DELETE OPERATIONS
    // ========================================================================

    /**
     * Soft delete (cancel) a contract.
     */
    Try<Void> softDelete(String tenantId, Long id, String user, String reason);

    // ========================================================================
    // VALIDATION
    // ========================================================================

    /**
     * Validate contract data.
     */
    List<ValidationResult> validate(Contract contract);

    /**
     * Get allowed status transitions for current status.
     */
    List<String> getAllowedTransitions(String currentStatus);

    // ========================================================================
    // ANALYTICS & BATCH OPERATIONS
    // ========================================================================

    /**
     * Calculate total value of a contract (including line items).
     * @return Optional.empty() if contract does not exist, Optional.of(total) otherwise
     */
    Optional<BigDecimal> calculateContractTotal(String tenantId, Long contractId);

    /**
     * Get statistics for contracts within a date range.
     */
    ContractStatistics getStatistics(String tenantId, LocalDate startDate, LocalDate endDate);

    /**
     * Check if a contract is expiring within the given days threshold.
     * @return Optional.empty() if contract does not exist, Optional.of(true/false) if found
     */
    Optional<Boolean> isExpiringSoon(String tenantId, Long contractId, int daysThreshold);

    /**
     * Check if a status transition is valid according to state machine.
     */
    boolean isValidTransition(String currentStatus, String newStatus);

    /**
     * Process automatic renewals for eligible contracts.
     * @return Try wrapping the result for consistent batch error handling
     */
    Try<AutoRenewalResult> processAutoRenewals(String tenantId, String user);

    // ========================================================================
    // FILTER RECORD
    // ========================================================================

    record ContractFilter(
        String tenantId,
        Optional<String> status,
        Optional<Long> customerId,
        Optional<LocalDate> startDate,
        Optional<LocalDate> endDate,
        Optional<String> contractType
    ) {
        public static ContractFilter forTenant(String tenantId) {
            return new ContractFilter(
                tenantId,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
            );
        }

        public ContractFilter withStatus(String status) {
            return new ContractFilter(tenantId, Optional.of(status), customerId, startDate, endDate, contractType);
        }

        public ContractFilter withCustomer(Long customerId) {
            return new ContractFilter(tenantId, status, Optional.of(customerId), startDate, endDate, contractType);
        }

        public ContractFilter withDateRange(LocalDate start, LocalDate end) {
            return new ContractFilter(tenantId, status, customerId, Optional.of(start), Optional.of(end), contractType);
        }

        public ContractFilter withContractType(String ct) {
            return new ContractFilter(tenantId, status, customerId, startDate, endDate, Optional.of(ct));
        }

        public ContractFilter withStartDate(LocalDate start) {
            return new ContractFilter(tenantId, status, customerId, Optional.of(start), endDate, contractType);
        }

        public ContractFilter withEndDate(LocalDate end) {
            return new ContractFilter(tenantId, status, customerId, startDate, Optional.of(end), contractType);
        }
    }
}
