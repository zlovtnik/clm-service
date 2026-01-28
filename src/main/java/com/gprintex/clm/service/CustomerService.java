package com.gprintex.clm.service;

import com.gprintex.clm.config.ClmProperties;
import com.gprintex.clm.domain.Customer;
import com.gprintex.clm.domain.TransformMetadata;
import com.gprintex.clm.domain.ValidationResult;
import com.gprintex.clm.repository.CustomerRepository;
import com.gprintex.clm.repository.CustomerRepository.CustomerFilter;
import io.vavr.control.Either;
import io.vavr.control.Try;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Customer service with functional programming patterns.
 */
@Service
public class CustomerService {

    private final CustomerRepository repository;
    private final ClmProperties properties;

    public CustomerService(CustomerRepository repository, ClmProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    // ========================================================================
    // CREATE
    // ========================================================================

    @Transactional
    public Either<List<ValidationResult>, Customer> create(Customer customer, String user) {
        return repository.insert(customer, user);
    }

    @Transactional
    public Try<TransformMetadata> bulkUpsert(List<Customer> customers, String user) {
        return repository.bulkUpsert(customers, user, "CUSTOMER_CODE");
    }

    // ========================================================================
    // READ
    // ========================================================================

    @Transactional(readOnly = true)
    public Optional<Customer> findById(String tenantId, Long id) {
        return repository.findById(tenantId, id);
    }

    @Transactional(readOnly = true)
    public Optional<Customer> findByCode(String tenantId, String customerCode) {
        return repository.findByCode(tenantId, customerCode);
    }

    @Transactional(readOnly = true)
    public List<Customer> findByFilter(CustomerFilter filter) {
        return repository.findByFilter(filter).toList();
    }

    @Transactional(readOnly = true)
    public List<Customer> findActive(String tenantId) {
        return findByFilter(CustomerFilter.forTenant(tenantId).onlyActive());
    }

    @Transactional(readOnly = true)
    public List<Customer> search(String tenantId, String term) {
        return findByFilter(CustomerFilter.forTenant(tenantId).search(term));
    }

    @Transactional(readOnly = true)
    public long count(String tenantId, Boolean active) {
        return repository.count(tenantId, active);
    }

    // ========================================================================
    // UPDATE
    // ========================================================================

    @Transactional
    public Either<ValidationResult, Customer> update(Customer customer, String user) {
        return repository.update(customer, user);
    }

    @Transactional
    public Try<Void> activate(String tenantId, Long id, String user) {
        return repository.setActive(tenantId, id, true, user);
    }

    @Transactional
    public Try<Void> deactivate(String tenantId, Long id, String user) {
        return repository.setActive(tenantId, id, false, user);
    }

    // ========================================================================
    // VALIDATION
    // ========================================================================

    public List<ValidationResult> validate(Customer customer) {
        return repository.validate(customer);
    }

    public ValidationResult validateTaxId(String taxId, String customerType) {
        return repository.validateTaxId(taxId, customerType);
    }

    public ValidationResult validateEmail(String email) {
        return repository.validateEmail(email);
    }

    // ========================================================================
    // FUNCTIONAL COMBINATORS
    // ========================================================================

    @Transactional(readOnly = true)
    public <R> R withCustomer(String tenantId, Long id, Function<Customer, R> action, R defaultValue) {
        return findById(tenantId, id)
            .map(action)
            .orElse(defaultValue);
    }

    @Transactional(readOnly = true)
    public List<Customer> findMatching(String tenantId, Predicate<Customer> predicate) {
        return findByFilter(CustomerFilter.forTenant(tenantId))
            .stream()
            .filter(predicate)
            .toList();
    }
}
