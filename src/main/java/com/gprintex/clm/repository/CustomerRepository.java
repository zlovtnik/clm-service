package com.gprintex.clm.repository;

import com.gprintex.clm.domain.Customer;
import com.gprintex.clm.domain.TransformMetadata;
import com.gprintex.clm.domain.ValidationResult;
import io.vavr.control.Either;
import io.vavr.control.Try;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Functional repository interface for Customer operations.
 */
public interface CustomerRepository {

    // ========================================================================
    // INSERT OPERATIONS
    // ========================================================================

    Either<List<ValidationResult>, Customer> insert(Customer customer, String user);

    Try<TransformMetadata> bulkUpsert(List<Customer> customers, String user, String mergeOn);

    // ========================================================================
    // QUERY OPERATIONS
    // ========================================================================

    Optional<Customer> findById(String tenantId, Long id);

    Optional<Customer> findByCode(String tenantId, String customerCode);

    Stream<Customer> findByFilter(CustomerFilter filter);

    long count(String tenantId, Boolean active);

    // ========================================================================
    // UPDATE OPERATIONS
    // ========================================================================

    Either<ValidationResult, Customer> update(Customer customer, String user);

    Try<Void> setActive(String tenantId, Long id, boolean active, String user);

    // ========================================================================
    // VALIDATION
    // ========================================================================

    List<ValidationResult> validate(Customer customer);

    ValidationResult validateTaxId(String taxId, String customerType);

    ValidationResult validateEmail(String email);

    // ========================================================================
    // FILTER RECORD
    // ========================================================================

    record CustomerFilter(
        String tenantId,
        Optional<Boolean> active,
        Optional<String> customerType,
        Optional<String> searchTerm
    ) {
        public static CustomerFilter forTenant(String tenantId) {
            return new CustomerFilter(tenantId, Optional.empty(), Optional.empty(), Optional.empty());
        }

        public CustomerFilter onlyActive() {
            return new CustomerFilter(tenantId, Optional.of(true), customerType, searchTerm);
        }

        public CustomerFilter onlyInactive() {
            return new CustomerFilter(tenantId, Optional.of(false), customerType, searchTerm);
        }

        public CustomerFilter withType(String type) {
            return new CustomerFilter(tenantId, active, Optional.of(type), searchTerm);
        }

        public CustomerFilter search(String term) {
            return new CustomerFilter(tenantId, active, customerType, Optional.of(term));
        }
    }
}
