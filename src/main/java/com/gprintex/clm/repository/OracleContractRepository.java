package com.gprintex.clm.repository;

import com.gprintex.clm.config.SimpleJdbcCallFactory;
import com.gprintex.clm.domain.AutoRenewalResult;
import com.gprintex.clm.domain.Contract;
import com.gprintex.clm.domain.ContractStatistics;
import com.gprintex.clm.domain.ContractStatus;
import com.gprintex.clm.domain.TransformMetadata;
import com.gprintex.clm.domain.ValidationResult;
import io.vavr.control.Either;
import io.vavr.control.Try;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlOutParameter;
import org.springframework.jdbc.core.SqlParameter;
import org.springframework.jdbc.core.simple.SimpleJdbcCall;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;

/**
 * Oracle implementation of ContractRepository using stored procedures.
 * Calls contract_pkg functions and procedures.
 */
@Repository
public class OracleContractRepository implements ContractRepository {

    private static final String PKG_NAME = "CONTRACT_PKG";

    private final JdbcTemplate jdbcTemplate;
    private final SimpleJdbcCallFactory callFactory;

    public OracleContractRepository(JdbcTemplate jdbcTemplate, SimpleJdbcCallFactory callFactory) {
        this.jdbcTemplate = jdbcTemplate;
        this.callFactory = callFactory;
    }

    @Override
    public Either<List<ValidationResult>, Contract> insert(Contract contract, String user) {
        // First validate
        var validationErrors = validate(contract);
        if (!validationErrors.isEmpty() && validationErrors.stream().anyMatch(v -> !v.valid())) {
            return Either.left(validationErrors);
        }

        var call = callFactory.forFunction(PKG_NAME, "INSERT_CONTRACT")
            .declareParameters(
                new SqlOutParameter("RETURN", Types.NUMERIC),
                new SqlParameter("P_CONTRACT", Types.STRUCT, "CONTRACT_T"),
                new SqlParameter("P_USER", Types.VARCHAR)
            );

        try {
            Map<String, Object> params = new HashMap<>();
            params.put("P_CONTRACT", toOracleStruct(contract));
            params.put("P_USER", user);

            var result = call.execute(params);
            var returnValue = result.get("RETURN");
            
            if (returnValue == null || !(returnValue instanceof Number)) {
                return Either.left(List.of(
                    ValidationResult.error("INSERT_FAILED", "Missing return value from insert")
                ));
            }
            
            var newId = ((Number) returnValue).longValue();
            return Either.right(contract.withId(newId));
        } catch (Exception e) {
            // Log the full exception for debugging
            java.util.logging.Logger.getLogger(OracleContractRepository.class.getName())
                .log(java.util.logging.Level.SEVERE, "Error inserting contract", e);
            return Either.left(List.of(
                ValidationResult.error("INSERT_FAILED", e.getMessage())
            ));
        }
    }

    @Override
    public Try<TransformMetadata> bulkInsert(List<Contract> contracts, String user) {
        return Try.of(() -> {
            var call = callFactory.forProcedure(PKG_NAME, "BULK_INSERT_CONTRACTS")
                .declareParameters(
                    new SqlParameter("P_CONTRACTS", Types.ARRAY, "CONTRACT_TAB"),
                    new SqlParameter("P_USER", Types.VARCHAR),
                    new SqlOutParameter("P_METADATA", Types.STRUCT, "TRANSFORM_METADATA_T"),
                    new SqlOutParameter("P_ERRORS", Types.ARRAY, "VALIDATION_RESULTS_TAB")
                );

            Map<String, Object> params = new HashMap<>();
            params.put("P_CONTRACTS", contracts.stream().map(this::toOracleStruct).toList());
            params.put("P_USER", user);

            var result = call.execute(params);
            return mapToTransformMetadata(result.get("P_METADATA"));
        });
    }

    @Override
    public Optional<Contract> findById(String tenantId, Long id) {
        var sql = """
            SELECT * FROM TABLE(contract_pkg.get_contracts_by_filter(
                p_tenant_id => ?,
                p_status => NULL,
                p_customer_id => NULL,
                p_start_date => NULL,
                p_end_date => NULL,
                p_contract_type => NULL
            )) WHERE id = ?
            """;
        
        return jdbcTemplate.query(sql, contractRowMapper(), tenantId, id)
            .stream()
            .findFirst();
    }

    @Override
    public Optional<Contract> findByNumber(String tenantId, String contractNumber) {
        var call = callFactory.forFunction(PKG_NAME, "GET_CONTRACT_BY_NUMBER")
            .returningResultSet("RETURN", contractRowMapper());

        Map<String, Object> params = Map.of(
            "P_TENANT_ID", tenantId,
            "P_CONTRACT_NUMBER", contractNumber
        );

        try {
            var result = call.execute(params);
            @SuppressWarnings("unchecked")
            var contracts = (List<Contract>) result.get("RETURN");
            return contracts.stream().findFirst();
        } catch (Exception e) {
            // Log the exception for debugging instead of silently swallowing
            java.util.logging.Logger.getLogger(OracleContractRepository.class.getName())
                .log(java.util.logging.Level.SEVERE, "Error finding contract by number: " + contractNumber, e);
            throw new RuntimeException("Error finding contract by number", e);
        }
    }

    @Override
    public Stream<Contract> findByFilter(ContractFilter filter) {
        var sql = """
            SELECT * FROM TABLE(contract_pkg.get_contracts_by_filter(
                p_tenant_id => ?,
                p_status => ?,
                p_customer_id => ?,
                p_start_date => ?,
                p_end_date => ?,
                p_contract_type => ?
            ))
            """;

        return jdbcTemplate.queryForStream(
            sql,
            contractRowMapper(),
            filter.tenantId(),
            filter.status().orElse(null),
            filter.customerId().orElse(null),
            filter.startDate().map(java.sql.Date::valueOf).orElse(null),
            filter.endDate().map(java.sql.Date::valueOf).orElse(null),
            filter.contractType().orElse(null)
        );
    }

    @Override
    public long count(String tenantId, String status, Long customerId) {
        var call = callFactory.forFunction(PKG_NAME, "COUNT_CONTRACTS")
            .declareParameters(
                new SqlOutParameter("RETURN", Types.NUMERIC),
                new SqlParameter("P_TENANT_ID", Types.VARCHAR),
                new SqlParameter("P_STATUS", Types.VARCHAR),
                new SqlParameter("P_CUSTOMER_ID", Types.NUMERIC)
            );

        Map<String, Object> params = new HashMap<>();
        params.put("P_TENANT_ID", tenantId);
        params.put("P_STATUS", status);
        params.put("P_CUSTOMER_ID", customerId);

        var result = call.execute(params);
        return ((Number) result.get("RETURN")).longValue();
    }

    @Override
    public Either<ValidationResult, Contract> update(Contract contract, String user) {
        var call = callFactory.forProcedure(PKG_NAME, "UPDATE_CONTRACT")
            .declareParameters(
                new SqlParameter("P_CONTRACT", Types.STRUCT, "CONTRACT_T"),
                new SqlParameter("P_USER", Types.VARCHAR),
                new SqlOutParameter("P_VALIDATION", Types.STRUCT, "VALIDATION_RESULT_T")
            );

        Map<String, Object> params = new HashMap<>();
        params.put("P_CONTRACT", toOracleStruct(contract));
        params.put("P_USER", user);

        var result = call.execute(params);
        var validation = mapToValidationResult(result.get("P_VALIDATION"));

        if (validation.valid()) {
            // Re-fetch the persisted record to return fresh data
            return contract.id()
                .flatMap(id -> findById(contract.tenantId(), id))
                .map(Either::<ValidationResult, Contract>right)
                .orElse(Either.right(contract));
        }
        return Either.left(validation);
    }

    @Override
    public Either<ValidationResult, Contract> updateStatus(
        String tenantId, Long id, String newStatus, String user, String reason
    ) {
        var call = callFactory.forProcedure(PKG_NAME, "UPDATE_CONTRACT_STATUS")
            .declareParameters(
                new SqlParameter("P_TENANT_ID", Types.VARCHAR),
                new SqlParameter("P_ID", Types.NUMERIC),
                new SqlParameter("P_NEW_STATUS", Types.VARCHAR),
                new SqlParameter("P_USER", Types.VARCHAR),
                new SqlParameter("P_REASON", Types.VARCHAR),
                new SqlOutParameter("P_VALIDATION", Types.STRUCT, "VALIDATION_RESULT_T")
            );

        Map<String, Object> params = new HashMap<>();
        params.put("P_TENANT_ID", tenantId);
        params.put("P_ID", id);
        params.put("P_NEW_STATUS", newStatus);
        params.put("P_USER", user);
        params.put("P_REASON", reason);

        var result = call.execute(params);
        var validation = mapToValidationResult(result.get("P_VALIDATION"));

        if (validation.valid()) {
            return findById(tenantId, id)
                .map(Either::<ValidationResult, Contract>right)
                .orElse(Either.left(ValidationResult.error("NOT_FOUND", "Contract not found after update")));
        }
        return Either.left(validation);
    }

    @Override
    public Try<Void> softDelete(String tenantId, Long id, String user, String reason) {
        return Try.run(() -> {
            var call = callFactory.forProcedure(PKG_NAME, "SOFT_DELETE_CONTRACT");

            Map<String, Object> params = Map.of(
                "P_TENANT_ID", tenantId,
                "P_ID", id,
                "P_USER", user,
                "P_REASON", reason != null ? reason : ""
            );

            call.execute(params);
        });
    }

    @Override
    public List<ValidationResult> validate(Contract contract) {
        // Use Java-side validation for basic checks
        var results = new ArrayList<ValidationResult>();

        if (contract.tenantId() == null || contract.tenantId().isBlank()) {
            results.add(ValidationResult.error("REQUIRED", "Tenant ID is required", "tenantId"));
        }
        if (contract.contractNumber() == null || contract.contractNumber().isBlank()) {
            results.add(ValidationResult.error("REQUIRED", "Contract number is required", "contractNumber"));
        }
        if (contract.customerId() == null) {
            results.add(ValidationResult.error("REQUIRED", "Customer ID is required", "customerId"));
        }
        if (contract.startDate() == null) {
            results.add(ValidationResult.error("REQUIRED", "Start date is required", "startDate"));
        }
        if (contract.endDate().isPresent() && contract.startDate() != null 
            && contract.endDate().get().isBefore(contract.startDate())) {
            results.add(ValidationResult.error("INVALID_DATE_RANGE", "End date must be after start date", "endDate"));
        }

        return results.isEmpty() ? List.of(ValidationResult.success()) : results;
    }

    @Override
    public List<String> getAllowedTransitions(String currentStatus) {
        try {
            var status = ContractStatus.valueOf(currentStatus);
            return Arrays.stream(ContractStatus.values())
                .filter(status::canTransitionTo)
                .map(Enum::name)
                .toList();
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    @Override
    public Optional<BigDecimal> calculateContractTotal(String tenantId, Long contractId) {
        var call = callFactory.forFunction(PKG_NAME, "CALCULATE_CONTRACT_TOTAL")
            .declareParameters(
                new SqlOutParameter("RETURN", Types.NUMERIC),
                new SqlParameter("P_TENANT_ID", Types.VARCHAR),
                new SqlParameter("P_CONTRACT_ID", Types.NUMERIC)
            );

        Map<String, Object> params = Map.of(
            "P_TENANT_ID", tenantId,
            "P_CONTRACT_ID", contractId
        );

        var result = call.execute(params);
        var returnValue = result.get("RETURN");
        
        if (returnValue == null) {
            return Optional.empty();
        }
        
        BigDecimal total;
        if (returnValue instanceof BigDecimal bd) {
            total = bd;
        } else if (returnValue instanceof java.math.BigInteger bi) {
            total = new BigDecimal(bi);
        } else if (returnValue instanceof Number n) {
            // Use toString() to preserve exact decimal digits, avoid doubleValue() precision loss
            total = new BigDecimal(returnValue.toString());
        } else {
            return Optional.empty();
        }
        
        return Optional.of(total);
    }

    @Override
    public ContractStatistics getStatistics(String tenantId, LocalDate startDate, LocalDate endDate) {
        var call = callFactory.forFunction(PKG_NAME, "GET_CONTRACT_STATISTICS")
            .returningResultSet("RETURN", (rs, rowNum) -> new ContractStatistics(
                rs.getLong("TOTAL_CONTRACTS"),
                rs.getLong("ACTIVE_CONTRACTS"),
                rs.getLong("PENDING_CONTRACTS"),
                rs.getLong("EXPIRED_CONTRACTS"),
                rs.getLong("CANCELLED_CONTRACTS"),
                rs.getBigDecimal("TOTAL_VALUE"),
                rs.getBigDecimal("AVERAGE_VALUE"),
                rs.getLong("EXPIRING_WITHIN_30_DAYS"),
                rs.getLong("AUTO_RENEW_ENABLED")
            ));

        Map<String, Object> params = new HashMap<>();
        params.put("P_TENANT_ID", tenantId);
        params.put("P_START_DATE", startDate != null ? java.sql.Date.valueOf(startDate) : null);
        params.put("P_END_DATE", endDate != null ? java.sql.Date.valueOf(endDate) : null);

        try {
            var result = call.execute(params);
            @SuppressWarnings("unchecked")
            var stats = (List<ContractStatistics>) result.get("RETURN");
            return stats.stream().findFirst().orElse(ContractStatistics.empty());
        } catch (Exception e) {
            java.util.logging.Logger.getLogger(OracleContractRepository.class.getName())
                .log(java.util.logging.Level.WARNING, "Error getting statistics", e);
            return ContractStatistics.empty();
        }
    }

    @Override
    public Optional<Boolean> isExpiringSoon(String tenantId, Long contractId, int daysThreshold) {
        // First check if contract exists
        if (findById(tenantId, contractId).isEmpty()) {
            return Optional.empty();
        }
        
        var call = callFactory.forFunction(PKG_NAME, "IS_EXPIRING_SOON")
            .declareParameters(
                new SqlOutParameter("RETURN", Types.NUMERIC),
                new SqlParameter("P_TENANT_ID", Types.VARCHAR),
                new SqlParameter("P_CONTRACT_ID", Types.NUMERIC),
                new SqlParameter("P_DAYS_THRESHOLD", Types.NUMERIC)
            );

        Map<String, Object> params = Map.of(
            "P_TENANT_ID", tenantId,
            "P_CONTRACT_ID", contractId,
            "P_DAYS_THRESHOLD", daysThreshold
        );

        var result = call.execute(params);
        var returnValue = result.get("RETURN");
        if (returnValue == null) {
            return Optional.empty();
        }
        if (!(returnValue instanceof Number)) {
            log.warn("isExpiringSoon returned non-numeric value: {}", returnValue.getClass().getName());
            return Optional.empty();
        }
        return Optional.of(((Number) returnValue).intValue() == 1);
    }

    @Override
    public boolean isValidTransition(String currentStatus, String newStatus) {
        var call = callFactory.forFunction(PKG_NAME, "IS_VALID_TRANSITION")
            .declareParameters(
                new SqlOutParameter("RETURN", Types.NUMERIC),
                new SqlParameter("P_CURRENT_STATUS", Types.VARCHAR),
                new SqlParameter("P_NEW_STATUS", Types.VARCHAR)
            );

        Map<String, Object> params = Map.of(
            "P_CURRENT_STATUS", currentStatus,
            "P_NEW_STATUS", newStatus
        );

        var result = call.execute(params);
        var returnValue = result.get("RETURN");
        return returnValue instanceof Number && ((Number) returnValue).intValue() == 1;
    }

    @Override
    public Try<AutoRenewalResult> processAutoRenewals(String tenantId, String user) {
        return Try.of(() -> {
            var call = callFactory.forProcedure(PKG_NAME, "PROCESS_AUTO_RENEWALS")
                .declareParameters(
                    new SqlParameter("P_TENANT_ID", Types.VARCHAR),
                    new SqlParameter("P_USER", Types.VARCHAR),
                    new SqlOutParameter("P_RENEWED_COUNT", Types.NUMERIC),
                    new SqlOutParameter("P_ERRORS", Types.CLOB)
                );

            Map<String, Object> params = Map.of(
                "P_TENANT_ID", tenantId,
                "P_USER", user
            );

            var result = call.execute(params);
            int renewedCount = result.get("P_RENEWED_COUNT") instanceof Number 
                ? ((Number) result.get("P_RENEWED_COUNT")).intValue() : 0;
            
            // Handle both String and Clob types for P_ERRORS
            Object errorsObj = result.get("P_ERRORS");
            String errorsText = null;
            if (errorsObj instanceof String s) {
                errorsText = s;
            } else if (errorsObj instanceof java.sql.Clob clob) {
                try {
                    errorsText = clob.getSubString(1, (int) clob.length());
                } catch (java.sql.SQLException e) {
                    log.warn("Error reading P_ERRORS Clob", e);
                } finally {
                    try {
                        clob.free();
                    } catch (java.sql.SQLException e) {
                        log.warn("Error freeing P_ERRORS Clob", e);
                    }
                }
            }
            
            List<String> errors = errorsText != null && !errorsText.isBlank()
                ? Arrays.asList(errorsText.split("\n"))
                : List.of();

            return new AutoRenewalResult(renewedCount, errors);
        });
    }

    // ========================================================================
    // PRIVATE HELPERS
    // ========================================================================

    private RowMapper<Contract> contractRowMapper() {
        return (rs, rowNum) -> mapResultSetToContract(rs);
    }

    private Contract mapResultSetToContract(ResultSet rs) throws SQLException {
        // Handle START_DATE null-safely
        java.sql.Date startDateSql = rs.getDate("START_DATE");
        LocalDate startDate = startDateSql != null ? startDateSql.toLocalDate() : null;
        
        // Handle CUSTOMER_ID null-safely (rs.getLong returns 0 for NULL)
        Long customerId = rs.getObject("CUSTOMER_ID", Long.class);
        
        return new Contract(
            Optional.ofNullable(rs.getObject("ID", Long.class)),
            rs.getString("TENANT_ID"),
            rs.getString("CONTRACT_NUMBER"),
            Optional.ofNullable(rs.getString("CONTRACT_TYPE")),
            customerId,
            startDate,
            Optional.ofNullable(rs.getDate("END_DATE")).map(java.sql.Date::toLocalDate),
            Optional.ofNullable(rs.getObject("DURATION_MONTHS", Integer.class)),
            rs.getInt("AUTO_RENEW") == 1,
            Optional.ofNullable(rs.getBigDecimal("TOTAL_VALUE")),
            Optional.ofNullable(rs.getString("PAYMENT_TERMS")),
            Optional.ofNullable(rs.getString("BILLING_CYCLE")),
            rs.getString("STATUS"),
            Optional.ofNullable(rs.getTimestamp("SIGNED_AT")).map(Timestamp::toLocalDateTime),
            Optional.ofNullable(rs.getString("SIGNED_BY")),
            Optional.ofNullable(rs.getString("NOTES")),
            Optional.ofNullable(rs.getTimestamp("CREATED_AT")).map(Timestamp::toLocalDateTime),
            Optional.ofNullable(rs.getTimestamp("UPDATED_AT")).map(Timestamp::toLocalDateTime),
            Optional.ofNullable(rs.getString("CREATED_BY")),
            Optional.ofNullable(rs.getString("UPDATED_BY"))
        );
    }

    private Map<String, Object> toOracleStruct(Contract contract) {
        Map<String, Object> struct = new HashMap<>();
        contract.id().ifPresent(id -> struct.put("ID", id));
        struct.put("TENANT_ID", contract.tenantId());
        struct.put("CONTRACT_NUMBER", contract.contractNumber());
        contract.contractType().ifPresent(t -> struct.put("CONTRACT_TYPE", t));
        struct.put("CUSTOMER_ID", contract.customerId());
        // Null-check startDate before converting
        if (contract.startDate() != null) {
            struct.put("START_DATE", java.sql.Date.valueOf(contract.startDate()));
        }
        contract.endDate().ifPresent(d -> struct.put("END_DATE", java.sql.Date.valueOf(d)));
        contract.durationMonths().ifPresent(m -> struct.put("DURATION_MONTHS", m));
        struct.put("AUTO_RENEW", contract.autoRenew() ? 1 : 0);
        contract.totalValue().ifPresent(v -> struct.put("TOTAL_VALUE", v));
        contract.paymentTerms().ifPresent(t -> struct.put("PAYMENT_TERMS", t));
        contract.billingCycle().ifPresent(c -> struct.put("BILLING_CYCLE", c));
        struct.put("STATUS", contract.status());
        contract.signedAt().ifPresent(t -> struct.put("SIGNED_AT", Timestamp.valueOf(t)));
        contract.signedBy().ifPresent(s -> struct.put("SIGNED_BY", s));
        contract.notes().ifPresent(n -> struct.put("NOTES", n));
        return struct;
    }

    private TransformMetadata mapToTransformMetadata(Object struct) {
        if (struct instanceof Map<?, ?> map) {
            var sourceSystem = (String) map.get("SOURCE_SYSTEM");
            var timestampObj = map.get("TRANSFORM_TIMESTAMP");
            var version = (String) map.get("TRANSFORM_VERSION");
            var recordCountObj = map.get("RECORD_COUNT");
            var successCountObj = map.get("SUCCESS_COUNT");
            var errorCountObj = map.get("ERROR_COUNT");
            
            return new TransformMetadata(
                sourceSystem != null ? sourceSystem : "UNKNOWN",
                timestampObj instanceof Timestamp ? ((Timestamp) timestampObj).toLocalDateTime() : LocalDateTime.now(),
                version != null ? version : "1.0",
                recordCountObj instanceof Number ? ((Number) recordCountObj).longValue() : 0L,
                successCountObj instanceof Number ? ((Number) successCountObj).longValue() : 0L,
                errorCountObj instanceof Number ? ((Number) errorCountObj).longValue() : 0L
            );
        }
        return TransformMetadata.create("UNKNOWN");
    }

    private ValidationResult mapToValidationResult(Object struct) {
        if (struct instanceof Map<?, ?> map) {
            var isValidObj = map.get("IS_VALID");
            boolean isValid = false;
            if (isValidObj instanceof Number) {
                isValid = ((Number) isValidObj).intValue() == 1;
            } else if (isValidObj instanceof Boolean) {
                isValid = (Boolean) isValidObj;
            }
            return new ValidationResult(
                isValid,
                (String) map.get("ERROR_CODE"),
                (String) map.get("ERROR_MESSAGE"),
                (String) map.get("FIELD_NAME")
            );
        }
        return ValidationResult.success();
    }
}
