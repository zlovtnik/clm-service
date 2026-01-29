package com.gprintex.clm.service;

import com.gprintex.clm.config.ClmProperties;
import com.gprintex.clm.domain.AutoRenewalResult;
import com.gprintex.clm.domain.Contract;
import com.gprintex.clm.domain.ContractStatistics;
import com.gprintex.clm.domain.TransformMetadata;
import com.gprintex.clm.domain.ValidationResult;
import com.gprintex.clm.repository.ContractRepository;
import com.gprintex.clm.repository.ContractRepository.ContractFilter;
import io.vavr.control.Either;
import io.vavr.control.Try;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Contract service with functional programming patterns.
 * Uses Either for error handling, Optional for nullable values, Stream for collections.
 */
@Service
public class ContractService {

    private final ContractRepository repository;
    private final ClmProperties properties;

    public ContractService(ContractRepository repository, ClmProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    // ========================================================================
    // CREATE
    // ========================================================================

    /**
     * Create a new contract with validation.
     */
    @Transactional
    public Either<List<ValidationResult>, Contract> create(Contract contract, String user) {
        return repository.insert(contract, user);
    }

    /**
     * Create contract with default tenant if not specified.
     */
    @Transactional
    public Either<List<ValidationResult>, Contract> createWithDefaults(Contract contract, String user) {
        var effectiveContract = (contract.tenantId() == null || contract.tenantId().isBlank())
            ? Contract.draft(
                properties.tenant().defaultId(),
                contract.contractNumber(),
                contract.customerId(),
                contract.startDate()
            )
            : contract;
        return create(effectiveContract, user);
    }

    // ========================================================================
    // READ - Functional query methods
    // ========================================================================

    /**
     * Find contract by ID.
     */
    @Transactional(readOnly = true)
    public Optional<Contract> findById(String tenantId, Long id) {
        return repository.findById(tenantId, id);
    }

    /**
     * Find contract by number.
     */
    @Transactional(readOnly = true)
    public Optional<Contract> findByNumber(String tenantId, String contractNumber) {
        return repository.findByNumber(tenantId, contractNumber);
    }

    /**
     * Find contracts by filter - returns a List (Stream materialized within transaction).
     */
    @Transactional(readOnly = true)
    public List<Contract> findByFilter(ContractFilter filter) {
        return repository.findByFilter(filter).toList();
    }

    /**
     * Find active contracts for a customer.
     */
    @Transactional(readOnly = true)
    public List<Contract> findActiveByCustomer(String tenantId, Long customerId) {
        return findByFilter(
            ContractFilter.forTenant(tenantId)
                .withCustomer(customerId)
                .withStatus("ACTIVE")
        );
    }

    /**
     * Find contracts matching a predicate.
     */
    @Transactional(readOnly = true)
    public List<Contract> findMatching(String tenantId, Predicate<Contract> predicate) {
        return findByFilter(ContractFilter.forTenant(tenantId))
            .stream()
            .filter(predicate)
            .toList();
    }

    /**
     * Transform and collect contracts.
     */
    @Transactional(readOnly = true)
    public <R> List<R> transformContracts(ContractFilter filter, Function<Contract, R> mapper) {
        return findByFilter(filter)
            .stream()
            .map(mapper)
            .toList();
    }

    /**
     * Count contracts matching filter.
     */
    @Transactional(readOnly = true)
    public long count(String tenantId, String status, Long customerId) {
        return repository.count(tenantId, status, customerId);
    }

    // ========================================================================
    // UPDATE
    // ========================================================================

    /**
     * Update contract.
     */
    @Transactional
    public Either<ValidationResult, Contract> update(Contract contract, String user) {
        return repository.update(contract, user);
    }

    /**
     * Update contract status with state machine validation.
     */
    @Transactional
    public Either<ValidationResult, Contract> updateStatus(
        String tenantId, Long id, String newStatus, String user, String reason
    ) {
        return repository.updateStatus(tenantId, id, newStatus, user, reason);
    }

    /**
     * Activate a contract (must be in PENDING status).
     */
    @Transactional
    public Either<ValidationResult, Contract> activate(String tenantId, Long id, String user) {
        return updateStatus(tenantId, id, "ACTIVE", user, "Contract activated");
    }

    /**
     * Suspend a contract.
     */
    @Transactional
    public Either<ValidationResult, Contract> suspend(String tenantId, Long id, String user, String reason) {
        return updateStatus(tenantId, id, "SUSPENDED", user, reason);
    }

    /**
     * Complete a contract.
     */
    @Transactional
    public Either<ValidationResult, Contract> complete(String tenantId, Long id, String user) {
        return updateStatus(tenantId, id, "COMPLETED", user, "Contract completed");
    }

    // ========================================================================
    // DELETE
    // ========================================================================

    /**
     * Soft delete (cancel) a contract.
     */
    @Transactional
    public Try<Void> softDelete(String tenantId, Long id, String user, String reason) {
        return repository.softDelete(tenantId, id, user, reason);
    }

    // ========================================================================
    // BULK OPERATIONS
    // ========================================================================

    /**
     * Bulk insert contracts with tenant validation.
     * @param tenantId The tenant ID to enforce on all contracts
     * @param contracts The contracts to insert
     * @param user The user performing the operation
     */
    @Transactional
    public Try<TransformMetadata> bulkInsert(String tenantId, List<Contract> contracts, String user) {
        return Try.of(() -> {
            // Validate and ensure all contracts have the correct tenant
            List<Contract> validatedContracts = new java.util.ArrayList<>();
            for (Contract c : contracts) {
                if (c.tenantId() != null && !c.tenantId().isBlank() && !c.tenantId().equals(tenantId)) {
                    throw new IllegalArgumentException(
                        String.format("Contract tenant ID mismatch: expected '%s', got '%s' for contract id=%s",
                            tenantId, c.tenantId(), c.id() != null ? c.id() : "new"));
                }
                validatedContracts.add(c.tenantId() == null || c.tenantId().isBlank() ? c.withTenantId(tenantId) : c);
            }
            return validatedContracts;
        }).flatMap(validatedContracts -> repository.bulkInsert(validatedContracts, user));
    }

    /**
     * Process automatic renewals for expiring contracts.
     */
    @Transactional
    public Try<AutoRenewalResult> processAutoRenewals(String tenantId, String user) {
        return repository.processAutoRenewals(tenantId, user);
    }

    // ========================================================================
    // ANALYTICS
    // ========================================================================

    /**
     * Calculate total value of a contract including line items.
     * @return Optional.empty() if contract does not exist
     */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> calculateContractTotal(String tenantId, Long contractId) {
        return repository.calculateContractTotal(tenantId, contractId);
    }

    /**
     * Get contract statistics for a time period.
     */
    @Transactional(readOnly = true)
    public ContractStatistics getStatistics(String tenantId, LocalDate startDate, LocalDate endDate) {
        return repository.getStatistics(tenantId, startDate, endDate);
    }

    /**
     * Check if a contract is expiring within threshold days.
     * @return Optional.empty() if contract does not exist
     */
    @Transactional(readOnly = true)
    public Optional<Boolean> isExpiringSoon(String tenantId, Long contractId, int daysThreshold) {
        return repository.isExpiringSoon(tenantId, contractId, daysThreshold);
    }

    /**
     * Check if a status transition is valid.
     */
    public boolean isValidTransition(String currentStatus, String newStatus) {
        return repository.isValidTransition(currentStatus, newStatus);
    }

    // ========================================================================
    // VALIDATION
    // ========================================================================

    /**
     * Validate contract data without persisting.
     */
    public List<ValidationResult> validate(Contract contract) {
        return repository.validate(contract);
    }

    /**
     * Check if a status transition is allowed.
     */
    public boolean canTransition(String fromStatus, String toStatus) {
        return repository.getAllowedTransitions(fromStatus).contains(toStatus);
    }

    /**
     * Get allowed status transitions.
     */
    public List<String> getAllowedTransitions(String currentStatus) {
        return repository.getAllowedTransitions(currentStatus);
    }

    // ========================================================================
    // FUNCTIONAL COMBINATORS
    // ========================================================================

    /**
     * Execute action on contract if found, otherwise return default.
     */
    public <R> R withContract(String tenantId, Long id, Function<Contract, R> action, R defaultValue) {
        return findById(tenantId, id)
            .map(action)
            .orElse(defaultValue);
    }

    /**
     * Execute action on contract or throw exception.
     */
    public <R> R withContractOrThrow(String tenantId, Long id, Function<Contract, R> action) {
        return findById(tenantId, id)
            .map(action)
            .orElseThrow(() -> new IllegalArgumentException("Contract not found: " + id));
    }

    /**
     * Chain operations on a contract.
     */
    @Transactional
    public Either<ValidationResult, Contract> chain(
        String tenantId,
        Long id,
        String user,
        Function<Contract, Contract> transformer
    ) {
        var contractOpt = findById(tenantId, id);
        if (contractOpt.isEmpty()) {
            return Either.left(ValidationResult.error("NOT_FOUND", "Contract not found"));
        }
        var transformed = transformer.apply(contractOpt.get());
        return update(transformed, user);
    }
}
