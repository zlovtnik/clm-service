package com.gprintex.clm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gprintex.clm.domain.Contract;
import com.gprintex.clm.domain.ContractStatistics;
import com.gprintex.clm.domain.AutoRenewalResult;
import com.gprintex.clm.domain.TransformMetadata;
import com.gprintex.clm.domain.ValidationResult;
import io.vavr.control.Either;
import io.vavr.control.Try;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * ORDS client specifically for Contract package operations.
 * Maps between Java domain objects and ORDS REST API calls.
 */
@Component
public class OrdsContractClient {

    private static final Logger log = LoggerFactory.getLogger(OrdsContractClient.class);
    private static final String PKG_NAME = "contract_pkg";

    private final OrdsClient ordsClient;
    private final ObjectMapper objectMapper;

    public OrdsContractClient(OrdsClient ordsClient, ObjectMapper objectMapper) {
        this.ordsClient = ordsClient;
        this.objectMapper = objectMapper;
    }

    // ========================================================================
    // INSERT OPERATIONS
    // ========================================================================

    public Either<List<ValidationResult>, Contract> insertContract(Contract contract, String user) {
        Map<String, Object> params = Map.of(
            "p_contract", contractToOrdsMap(contract),
            "p_user", user
        );

        try {
            JsonNode response = ordsClient.callProcedureSync(PKG_NAME, "INSERT_CONTRACT", params);
            
            // Check for validation errors
            if (response.has("p_validation") && !response.get("p_validation").isNull()) {
                ValidationResult validation = parseValidation(response.get("p_validation"));
                if (!validation.valid()) {
                    return Either.left(List.of(validation));
                }
            }
            
            // Get the new ID from response
            Long newId = response.has("~ret") ? response.get("~ret").asLong() : null;
            return Either.right(contract.withId(newId));
            
        } catch (OrdsClient.OrdsException e) {
            return Either.left(List.of(ValidationResult.error("ORDS_ERROR", e.getMessage())));
        }
    }

    public Try<TransformMetadata> bulkInsertContracts(List<Contract> contracts, String user) {
        Map<String, Object> params = Map.of(
            "p_contracts", contracts.stream().map(this::contractToOrdsMap).toList(),
            "p_user", user
        );

        return Try.of(() -> {
            JsonNode response = ordsClient.callProcedureSync(PKG_NAME, "BULK_INSERT_CONTRACTS", params);
            return parseTransformMetadata(response);
        });
    }

    // ========================================================================
    // QUERY OPERATIONS
    // ========================================================================

    public Optional<Contract> getContractById(String tenantId, Long id) {
        Map<String, Object> params = Map.of(
            "p_tenant_id", tenantId,
            "p_id", id
        );

        try {
            JsonNode response = ordsClient.callProcedureSync(PKG_NAME, "GET_CONTRACT_BY_ID", params);
            return parseContractFromResponse(response);
        } catch (OrdsClient.OrdsException e) {
            return Optional.empty();
        }
    }

    public Optional<Contract> getContractByNumber(String tenantId, String contractNumber) {
        Map<String, Object> params = Map.of(
            "p_tenant_id", tenantId,
            "p_contract_number", contractNumber
        );

        try {
            JsonNode response = ordsClient.callProcedureSync(PKG_NAME, "GET_CONTRACT_BY_NUMBER", params);
            return parseContractFromResponse(response);
        } catch (OrdsClient.OrdsException e) {
            return Optional.empty();
        }
    }

    public List<Contract> getContractsByFilter(String tenantId, String status, Long customerId,
                                                LocalDate startDate, LocalDate endDate, String contractType) {
        Map<String, Object> params = new HashMap<>();
        params.put("p_tenant_id", tenantId);
        if (status != null) params.put("p_status", status);
        if (customerId != null) params.put("p_customer_id", customerId);
        if (startDate != null) params.put("p_start_date", startDate.toString());
        if (endDate != null) params.put("p_end_date", endDate.toString());
        if (contractType != null) params.put("p_contract_type", contractType);

        try {
            JsonNode response = ordsClient.callProcedureSync(PKG_NAME, "GET_CONTRACTS_BY_FILTER", params);
            return parseContractList(response);
        } catch (OrdsClient.OrdsException e) {
            return List.of();
        }
    }

    public long countContracts(String tenantId, String status, Long customerId) {
        Map<String, Object> params = new HashMap<>();
        params.put("p_tenant_id", tenantId);
        if (status != null) params.put("p_status", status);
        if (customerId != null) params.put("p_customer_id", customerId);

        try {
            JsonNode response = ordsClient.callProcedureSync(PKG_NAME, "COUNT_CONTRACTS", params);
            return response.has("~ret") ? response.get("~ret").asLong() : 0;
        } catch (OrdsClient.OrdsException e) {
            return 0;
        }
    }

    // ========================================================================
    // UPDATE OPERATIONS
    // ========================================================================

    public Either<ValidationResult, Contract> updateContract(Contract contract, String user) {
        Map<String, Object> params = Map.of(
            "p_contract", contractToOrdsMap(contract),
            "p_user", user
        );

        try {
            JsonNode response = ordsClient.callProcedureSync(PKG_NAME, "UPDATE_CONTRACT", params);
            
            if (response.has("p_validation") && !response.get("p_validation").isNull()) {
                ValidationResult validation = parseValidation(response.get("p_validation"));
                if (!validation.valid()) {
                    return Either.left(validation);
                }
            }
            
            // Fetch and return fresh data after update
            Long id = contract.id().orElse(null);
            if (id == null) {
                return Either.left(ValidationResult.error("MISSING_ID", "Contract ID is required for update"));
            }
            return getContractById(contract.tenantId(), id)
                .map(Either::<ValidationResult, Contract>right)
                .orElse(Either.left(ValidationResult.error("NOT_FOUND", "Contract not found after update")));
            
        } catch (OrdsClient.OrdsException e) {
            log.error("Failed to update contract: {}", e.getMessage());
            return Either.left(ValidationResult.error("ORDS_ERROR", e.getMessage()));
        }
    }

    public Either<ValidationResult, Contract> updateContractStatus(String tenantId, Long id, 
                                                                    String newStatus, String user, String reason) {
        Map<String, Object> params = new HashMap<>();
        params.put("p_tenant_id", tenantId);
        params.put("p_id", id);
        params.put("p_new_status", newStatus);
        params.put("p_user", user);
        if (reason != null) params.put("p_reason", reason);

        try {
            JsonNode response = ordsClient.callProcedureSync(PKG_NAME, "UPDATE_CONTRACT_STATUS", params);
            
            if (response.has("p_validation") && !response.get("p_validation").isNull()) {
                ValidationResult validation = parseValidation(response.get("p_validation"));
                if (!validation.valid()) {
                    return Either.left(validation);
                }
            }
            
            // Fetch updated contract
            return getContractById(tenantId, id)
                .map(Either::<ValidationResult, Contract>right)
                .orElse(Either.left(ValidationResult.error("NOT_FOUND", "Contract not found after update")));
            
        } catch (OrdsClient.OrdsException e) {
            return Either.left(ValidationResult.error("ORDS_ERROR", e.getMessage()));
        }
    }

    // ========================================================================
    // DELETE OPERATIONS
    // ========================================================================

    public Try<Void> softDeleteContract(String tenantId, Long id, String user, String reason) {
        Map<String, Object> params = new HashMap<>();
        params.put("p_tenant_id", tenantId);
        params.put("p_id", id);
        params.put("p_user", user);
        if (reason != null) params.put("p_reason", reason);

        return Try.run(() -> {
            ordsClient.callProcedureSync(PKG_NAME, "SOFT_DELETE_CONTRACT", params);
        });
    }

    // ========================================================================
    // BUSINESS OPERATIONS
    // ========================================================================

    public AutoRenewalResult processAutoRenewals(String tenantId, String user) {
        Map<String, Object> params = Map.of(
            "p_tenant_id", tenantId,
            "p_user", user
        );

        try {
            JsonNode response = ordsClient.callProcedureSync(PKG_NAME, "PROCESS_AUTO_RENEWALS", params);
            int renewedCount = response.has("p_renewed_count") ? response.get("p_renewed_count").asInt() : 0;
            List<String> errors = new ArrayList<>();
            if (response.has("p_errors") && !response.get("p_errors").isNull()) {
                // Parse errors array
                response.get("p_errors").forEach(e -> errors.add(e.asText()));
            }
            return new AutoRenewalResult(renewedCount, errors);
        } catch (OrdsClient.OrdsException e) {
            return AutoRenewalResult.withErrors(0, List.of(e.getMessage()));
        }
    }

    public Optional<BigDecimal> calculateContractTotal(String tenantId, Long contractId) {
        Map<String, Object> params = Map.of(
            "p_tenant_id", tenantId,
            "p_contract_id", contractId
        );

        try {
            JsonNode response = ordsClient.callProcedureSync(PKG_NAME, "CALCULATE_CONTRACT_TOTAL", params);
            if (response.has("~ret") && !response.get("~ret").isNull()) {
                try {
                    return Optional.of(new BigDecimal(response.get("~ret").asText()));
                } catch (NumberFormatException e) {
                    log.warn("Invalid BigDecimal value for contract total: {}", response.get("~ret").asText());
                    return Optional.empty();
                }
            }
            return Optional.empty();
        } catch (OrdsClient.OrdsException e) {
            log.error("Failed to calculate contract total: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public ContractStatistics getContractStatistics(String tenantId, LocalDate startDate, LocalDate endDate) {
        Map<String, Object> params = new HashMap<>();
        params.put("p_tenant_id", tenantId);
        if (startDate != null) params.put("p_start_date", startDate.toString());
        if (endDate != null) params.put("p_end_date", endDate.toString());

        try {
            JsonNode response = ordsClient.callProcedureSync(PKG_NAME, "GET_CONTRACT_STATISTICS", params);
            return parseContractStatistics(response);
        } catch (OrdsClient.OrdsException e) {
            return ContractStatistics.empty();
        }
    }

    public Optional<Boolean> isExpiringSoon(String tenantId, Long contractId, int daysThreshold) {
        Map<String, Object> params = Map.of(
            "p_tenant_id", tenantId,
            "p_contract_id", contractId,
            "p_days_threshold", daysThreshold
        );

        try {
            JsonNode response = ordsClient.callProcedureSync(PKG_NAME, "IS_EXPIRING_SOON", params);
            if (response.has("~ret") && !response.get("~ret").isNull()) {
                return Optional.of(response.get("~ret").asInt() == 1);
            }
            return Optional.empty(); // Contract not found
        } catch (OrdsClient.OrdsException e) {
            log.error("Failed to check if contract is expiring soon: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public boolean isValidTransition(String currentStatus, String newStatus) {
        Map<String, Object> params = Map.of(
            "p_current_status", currentStatus,
            "p_new_status", newStatus
        );

        try {
            JsonNode response = ordsClient.callProcedureSync(PKG_NAME, "IS_VALID_TRANSITION", params);
            return response.has("~ret") && response.get("~ret").asInt() == 1;
        } catch (OrdsClient.OrdsException e) {
            return false;
        }
    }

    public List<String> getAllowedTransitions(String currentStatus) {
        Map<String, Object> params = Map.of("p_current_status", currentStatus);

        try {
            JsonNode response = ordsClient.callProcedureSync(PKG_NAME, "GET_ALLOWED_TRANSITIONS", params);
            List<String> transitions = new ArrayList<>();
            if (response.has("~ret") && response.get("~ret").isArray()) {
                response.get("~ret").forEach(t -> transitions.add(t.asText()));
            }
            return transitions;
        } catch (OrdsClient.OrdsException e) {
            return List.of();
        }
    }

    // ========================================================================
    // PRIVATE HELPERS
    // ========================================================================

    private Map<String, Object> contractToOrdsMap(Contract contract) {
        Map<String, Object> map = new HashMap<>();
        map.put("tenant_id", contract.tenantId());
        map.put("contract_number", contract.contractNumber());
        map.put("customer_id", contract.customerId());
        map.put("start_date", contract.startDate() != null ? contract.startDate().toString() : null);
        map.put("auto_renew", contract.autoRenew() ? 1 : 0);
        if (contract.status() != null) map.put("status", contract.status());
        
        // Handle Optional fields
        contract.id().ifPresent(v -> map.put("id", v));
        contract.contractType().ifPresent(v -> map.put("contract_type", v));
        contract.endDate().ifPresent(v -> map.put("end_date", v.toString()));
        contract.durationMonths().ifPresent(v -> map.put("duration_months", v));
        contract.totalValue().ifPresent(v -> map.put("total_value", v));
        contract.paymentTerms().ifPresent(v -> map.put("payment_terms", v));
        contract.billingCycle().ifPresent(v -> map.put("billing_cycle", v));
        contract.notes().ifPresent(v -> map.put("notes", v));
        
        return map;
    }

    private Optional<Contract> parseContractFromResponse(JsonNode response) {
        if (response == null || response.isNull() || response.isEmpty()) {
            return Optional.empty();
        }
        
        JsonNode data = response.has("~ret") ? response.get("~ret") : response;
        if (data.isArray() && data.size() > 0) {
            data = data.get(0);
        }
        
        if (data == null || data.isNull()) {
            return Optional.empty();
        }
        
        return Optional.of(parseContract(data));
    }

    private List<Contract> parseContractList(JsonNode response) {
        List<Contract> contracts = new ArrayList<>();
        
        JsonNode data = response.has("~ret") ? response.get("~ret") : response;
        if (data != null && data.isArray()) {
            data.forEach(node -> contracts.add(parseContract(node)));
        }
        
        return contracts;
    }

    private Contract parseContract(JsonNode node) {
        return new Contract(
            node.has("id") ? Optional.of(node.get("id").asLong()) : Optional.empty(),
            node.has("tenant_id") ? node.get("tenant_id").asText() : null,
            node.has("contract_number") ? node.get("contract_number").asText() : null,
            node.has("contract_type") && !node.get("contract_type").isNull() 
                ? Optional.of(node.get("contract_type").asText()) : Optional.empty(),
            node.has("customer_id") ? node.get("customer_id").asLong() : null,
            parseLocalDate(node, "start_date"),
            parseOptionalLocalDate(node, "end_date"),
            node.has("duration_months") && !node.get("duration_months").isNull() 
                ? Optional.of(node.get("duration_months").asInt()) : Optional.empty(),
            node.has("auto_renew") && node.get("auto_renew").asInt() == 1,
            parseOptionalBigDecimal(node, "total_value"),
            node.has("payment_terms") && !node.get("payment_terms").isNull() 
                ? Optional.of(node.get("payment_terms").asText()) : Optional.empty(),
            node.has("billing_cycle") && !node.get("billing_cycle").isNull() 
                ? Optional.of(node.get("billing_cycle").asText()) : Optional.empty(),
            node.has("status") ? node.get("status").asText() : null,
            parseOptionalLocalDateTime(node, "signed_at"),
            node.has("signed_by") && !node.get("signed_by").isNull() 
                ? Optional.of(node.get("signed_by").asText()) : Optional.empty(),
            node.has("notes") && !node.get("notes").isNull() 
                ? Optional.of(node.get("notes").asText()) : Optional.empty(),
            parseOptionalLocalDateTime(node, "created_at"),
            parseOptionalLocalDateTime(node, "updated_at"),
            node.has("created_by") && !node.get("created_by").isNull() 
                ? Optional.of(node.get("created_by").asText()) : Optional.empty(),
            node.has("updated_by") && !node.get("updated_by").isNull() 
                ? Optional.of(node.get("updated_by").asText()) : Optional.empty()
        );
    }

    private LocalDate parseLocalDate(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) {
            return null;
        }
        try {
            String value = node.get(field).asText();
            return LocalDate.parse(value.substring(0, Math.min(10, value.length())));
        } catch (DateTimeParseException e) {
            log.warn("Failed to parse date field '{}': {}", field, node.get(field).asText());
            return null;
        }
    }

    private Optional<LocalDate> parseOptionalLocalDate(JsonNode node, String field) {
        LocalDate date = parseLocalDate(node, field);
        return Optional.ofNullable(date);
    }

    private Optional<java.time.LocalDateTime> parseOptionalLocalDateTime(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) {
            return Optional.empty();
        }
        try {
            String value = node.get(field).asText();
            return Optional.of(java.time.LocalDateTime.parse(value.substring(0, Math.min(19, value.length()))));
        } catch (DateTimeParseException e) {
            log.warn("Failed to parse datetime field '{}': {}", field, node.get(field).asText());
            return Optional.empty();
        }
    }

    private Optional<BigDecimal> parseOptionalBigDecimal(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(node.get(field).asText()));
        } catch (NumberFormatException e) {
            log.warn("Failed to parse BigDecimal field '{}': {}", field, node.get(field).asText());
            return Optional.empty();
        }
    }

    private ValidationResult parseValidation(JsonNode node) {
        if (node == null || node.isNull()) {
            return ValidationResult.success();
        }
        
        boolean isValid = node.has("is_valid") && node.get("is_valid").asInt() == 1;
        String errorCode = node.has("error_code") ? node.get("error_code").asText() : null;
        String errorMessage = node.has("error_message") ? node.get("error_message").asText() : null;
        
        return isValid ? ValidationResult.success() : ValidationResult.error(errorCode, errorMessage);
    }

    private TransformMetadata parseTransformMetadata(JsonNode response) {
        JsonNode meta = response.has("p_metadata") ? response.get("p_metadata") : response;
        
        String sourceSystem = meta.has("operation_type") ? meta.get("operation_type").asText() : "BULK_INSERT";
        int recordCount = meta.has("record_count") ? meta.get("record_count").asInt() : 0;
        int successCount = meta.has("success_count") ? meta.get("success_count").asInt() : 0;
        int errorCount = meta.has("error_count") ? meta.get("error_count").asInt() : 0;
        
        return TransformMetadata.create(sourceSystem).withCounts(recordCount, successCount, errorCount);
    }

    private ContractStatistics parseContractStatistics(JsonNode response) {
        JsonNode data = response.has("~ret") ? response.get("~ret") : response;
        
        return new ContractStatistics(
            data.has("total_contracts") ? data.get("total_contracts").asLong() : 0,
            data.has("active_contracts") ? data.get("active_contracts").asLong() : 0,
            data.has("pending_contracts") ? data.get("pending_contracts").asLong() : 0,
            data.has("expired_contracts") ? data.get("expired_contracts").asLong() : 0,
            data.has("cancelled_contracts") ? data.get("cancelled_contracts").asLong() : 0,
            parseBigDecimalOrZero(data, "total_value"),
            parseBigDecimalOrZero(data, "average_value"),
            data.has("expiring_within_30_days") ? data.get("expiring_within_30_days").asLong() : 0,
            data.has("auto_renew_enabled") ? data.get("auto_renew_enabled").asLong() : 0
        );
    }

    private BigDecimal parseBigDecimalOrZero(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(node.get(field).asText());
        } catch (NumberFormatException e) {
            log.warn("Failed to parse BigDecimal field '{}': {}", field, node.get(field).asText());
            return BigDecimal.ZERO;
        }
    }
}
