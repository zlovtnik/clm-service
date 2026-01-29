package com.gprintex.clm.ords;

import com.fasterxml.jackson.databind.JsonNode;
import com.gprintex.clm.client.OrdsClient;
import io.vavr.control.Either;
import io.vavr.control.Try;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.List;

/**
 * ORDS client for Customer Package operations.
 * 
 * Maps to the customer_pkg Oracle package exposed via ORDS.
 */
@Component
public class OrdsCustomerClient {

    private static final Logger log = LoggerFactory.getLogger(OrdsCustomerClient.class);
    private static final String CUSTOMER_PKG = "customer_pkg";

    private final OrdsClient ordsClient;

    public OrdsCustomerClient(OrdsClient ordsClient) {
        this.ordsClient = ordsClient;
    }

    /**
     * Insert a new customer.
     * 
     * @param tenantId tenant identifier
     * @param customerData customer information
     * @return Either error or the created customer ID
     */
    public Either<String, Long> insertCustomer(String tenantId, Map<String, Object> customerData) {
        if (tenantId == null || tenantId.isBlank()) {
            return Either.left("tenantId is required");
        }
        log.debug("Inserting customer for tenant: {}", tenantId);
        
        Map<String, Object> params = new java.util.HashMap<>(customerData);
        params.put("p_tenant_id", tenantId);

        return ordsClient.callProcedure(CUSTOMER_PKG, "insert_customer", params)
                .flatMap(result -> {
                    if (!result.has("p_customer_id") || result.get("p_customer_id").isNull()) {
                        return Either.left("Response missing customer ID");
                    }
                    return Try.of(() -> result.get("p_customer_id").asLong())
                            .toEither()
                            .mapLeft(e -> "Failed to extract customer ID: " + e.getMessage());
                });
    }

    /**
     * Update an existing customer.
     * 
     * @param tenantId tenant identifier
     * @param customerId customer ID
     * @param updates map of field updates
     * @return Either error or success boolean
     */
    public Either<String, Boolean> updateCustomer(String tenantId, Long customerId, Map<String, Object> updates) {
        if (tenantId == null || tenantId.isBlank()) {
            return Either.left("tenantId is required");
        }
        if (customerId == null) {
            return Either.left("customerId is required");
        }
        log.debug("Updating customer {} for tenant: {}", customerId, tenantId);
        
        Map<String, Object> params = new java.util.HashMap<>(updates);
        params.put("p_tenant_id", tenantId);
        params.put("p_customer_id", customerId);

        return ordsClient.callProcedure(CUSTOMER_PKG, "update_customer", params)
                .map(result -> true);
    }

    /**
     * Get customer by ID.
     * 
     * @param tenantId tenant identifier
     * @param customerId customer ID
     * @return Either error or customer data
     */
    public Either<String, JsonNode> getCustomerById(String tenantId, Long customerId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Either.left("tenantId is required");
        }
        if (customerId == null) {
            return Either.left("customerId is required");
        }
        log.debug("Getting customer {} for tenant: {}", customerId, tenantId);
        
        Map<String, Object> params = Map.of(
            "p_tenant_id", tenantId,
            "p_customer_id", customerId
        );

        return ordsClient.callProcedure(CUSTOMER_PKG, "get_customer_by_id", params);
    }

    /**
     * Query customers with filters.
     * 
     * @param tenantId tenant identifier
     * @param filters query filters
     * @return Either error or list of customers
     */
    public Either<String, List<JsonNode>> queryCustomers(String tenantId, Map<String, Object> filters) {
        if (tenantId == null || tenantId.isBlank()) {
            return Either.left("tenantId is required");
        }
        // Log filter keys only, not values (may contain PII like name, email)
        log.debug("Querying customers for tenant: {} with filter keys: {}", tenantId, 
            filters != null ? filters.keySet() : "none");
        
        Map<String, Object> params = new java.util.HashMap<>(filters != null ? filters : Map.of());
        params.put("p_tenant_id", tenantId);

        return ordsClient.callProcedureForList(CUSTOMER_PKG, "query_customers", params, "customers");
    }

    /**
     * Search customers by name or external reference.
     * 
     * @param tenantId tenant identifier
     * @param searchTerm search term
     * @param limit max results
     * @return Either error or list of matching customers
     */
    public Either<String, List<JsonNode>> searchCustomers(String tenantId, String searchTerm, int limit) {
        if (tenantId == null || tenantId.isBlank()) {
            return Either.left("tenantId is required");
        }
        // Don't log searchTerm as it may contain PII
        log.debug("Searching customers for tenant: {} (limit: {})", tenantId, limit);
        
        Map<String, Object> params = Map.of(
            "p_tenant_id", tenantId,
            "p_search_term", searchTerm != null ? searchTerm : "",
            "p_limit", limit
        );

        return ordsClient.callProcedureForList(CUSTOMER_PKG, "search_customers", params, "customers");
    }

    /**
     * Get all contracts for a customer.
     * 
     * @param tenantId tenant identifier
     * @param customerId customer ID
     * @return Either error or list of contracts
     */
    public Either<String, List<JsonNode>> getCustomerContracts(String tenantId, Long customerId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Either.left("tenantId is required");
        }
        if (customerId == null) {
            return Either.left("customerId is required");
        }
        log.debug("Getting contracts for customer {} in tenant: {}", customerId, tenantId);
        
        Map<String, Object> params = Map.of(
            "p_tenant_id", tenantId,
            "p_customer_id", customerId
        );

        return ordsClient.callProcedureForList(CUSTOMER_PKG, "get_customer_contracts", params, "contracts");
    }

    /**
     * Merge (upsert) customer based on external reference.
     * 
     * @param tenantId tenant identifier
     * @param externalRef external reference ID
     * @param customerData customer information
     * @return Either error or merged customer ID
     */
    public Either<String, Long> mergeCustomer(String tenantId, String externalRef, Map<String, Object> customerData) {
        if (tenantId == null || tenantId.isBlank()) {
            return Either.left("tenantId is required");
        }
        if (externalRef == null || externalRef.isBlank()) {
            return Either.left("externalRef is required");
        }
        log.debug("Merging customer with external ref for tenant: {}", tenantId);
        
        Map<String, Object> params = new java.util.HashMap<>(customerData != null ? customerData : Map.of());
        params.put("p_tenant_id", tenantId);
        params.put("p_external_ref", externalRef);

        return ordsClient.callProcedure(CUSTOMER_PKG, "merge_customer", params)
                .flatMap(result -> {
                    if (!result.has("p_customer_id") || result.get("p_customer_id").isNull()) {
                        return Either.left("Response missing customer ID");
                    }
                    return Try.of(() -> result.get("p_customer_id").asLong())
                            .toEither()
                            .mapLeft(e -> "Failed to extract customer ID: " + e.getMessage());
                });
    }

    /**
     * Delete customer (soft delete).
     * 
     * @param tenantId tenant identifier
     * @param customerId customer ID
     * @return Either error or success boolean
     */
    public Either<String, Boolean> deleteCustomer(String tenantId, Long customerId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Either.left("tenantId is required");
        }
        if (customerId == null) {
            return Either.left("customerId is required");
        }
        log.debug("Deleting customer {} for tenant: {}", customerId, tenantId);
        
        Map<String, Object> params = Map.of(
            "p_tenant_id", tenantId,
            "p_customer_id", customerId
        );

        return ordsClient.callProcedure(CUSTOMER_PKG, "delete_customer", params)
                .map(result -> true);
    }

    /**
     * Validate customer data.
     * 
     * @param tenantId tenant identifier
     * @param customerData customer data to validate
     * @return Either error or validation result
     */
    public Either<String, JsonNode> validateCustomer(String tenantId, Map<String, Object> customerData) {
        if (tenantId == null || tenantId.isBlank()) {
            return Either.left("tenantId is required");
        }
        log.debug("Validating customer data for tenant: {}", tenantId);
        
        Map<String, Object> params = new java.util.HashMap<>(customerData != null ? customerData : Map.of());
        params.put("p_tenant_id", tenantId);
        
        return ordsClient.callProcedure(CUSTOMER_PKG, "validate_customer", params);
    }
}
