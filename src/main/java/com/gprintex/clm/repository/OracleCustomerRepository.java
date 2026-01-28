package com.gprintex.clm.repository;

import com.gprintex.clm.config.SimpleJdbcCallFactory;
import com.gprintex.clm.domain.Customer;
import com.gprintex.clm.domain.TransformMetadata;
import com.gprintex.clm.domain.ValidationResult;
import io.vavr.control.Either;
import io.vavr.control.Try;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlOutParameter;
import org.springframework.jdbc.core.SqlParameter;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.*;
import java.util.stream.Stream;

/**
 * Oracle implementation of CustomerRepository using stored procedures.
 */
@Repository
public class OracleCustomerRepository implements CustomerRepository {

    private static final String PKG_NAME = "CUSTOMER_PKG";

    private final JdbcTemplate jdbcTemplate;
    private final SimpleJdbcCallFactory callFactory;

    public OracleCustomerRepository(JdbcTemplate jdbcTemplate, SimpleJdbcCallFactory callFactory) {
        this.jdbcTemplate = jdbcTemplate;
        this.callFactory = callFactory;
    }

    @Override
    public Either<List<ValidationResult>, Customer> insert(Customer customer, String user) {
        var validationErrors = validate(customer);
        if (!validationErrors.isEmpty() && validationErrors.stream().anyMatch(v -> !v.valid())) {
            return Either.left(validationErrors);
        }

        var call = callFactory.forFunction(PKG_NAME, "INSERT_CUSTOMER")
            .declareParameters(
                new SqlOutParameter("RETURN", Types.NUMERIC),
                new SqlParameter("P_CUSTOMER", Types.STRUCT, "CUSTOMER_T"),
                new SqlParameter("P_USER", Types.VARCHAR)
            );

        try {
            Map<String, Object> params = new HashMap<>();
            params.put("P_CUSTOMER", toOracleStruct(customer));
            params.put("P_USER", user);

            var result = call.execute(params);
            var returnValue = result.get("RETURN");
            
            if (returnValue == null || !(returnValue instanceof Number)) {
                return Either.left(List.of(
                    ValidationResult.error("INSERT_FAILED", "Missing return value from insert")
                ));
            }
            
            var newId = ((Number) returnValue).longValue();

            return Either.right(customer.withId(newId));
        } catch (Exception e) {
            // Log for debugging
            java.util.logging.Logger.getLogger(OracleCustomerRepository.class.getName())
                .log(java.util.logging.Level.SEVERE, "Error inserting customer", e);
            return Either.left(List.of(
                ValidationResult.error("INSERT_FAILED", e.getMessage())
            ));
        }
    }

    @Override
    public Try<TransformMetadata> bulkUpsert(List<Customer> customers, String user, String mergeOn) {
        return Try.of(() -> {
            var call = callFactory.forProcedure(PKG_NAME, "BULK_UPSERT_CUSTOMERS")
                .declareParameters(
                    new SqlParameter("P_CUSTOMERS", Types.ARRAY, "CUSTOMER_TAB"),
                    new SqlParameter("P_USER", Types.VARCHAR),
                    new SqlParameter("P_MERGE_ON", Types.VARCHAR),
                    new SqlOutParameter("P_METADATA", Types.STRUCT, "TRANSFORM_METADATA_T")
                );

            Map<String, Object> params = new HashMap<>();
            params.put("P_CUSTOMERS", customers.stream().map(this::toOracleStruct).toList());
            params.put("P_USER", user);
            params.put("P_MERGE_ON", mergeOn);

            var result = call.execute(params);
            return mapToTransformMetadata(result.get("P_METADATA"));
        });
    }

    @Override
    public Optional<Customer> findById(String tenantId, Long id) {
        var sql = """
            SELECT * FROM TABLE(customer_pkg.get_customers_by_filter(
                p_tenant_id => ?,
                p_active => NULL,
                p_customer_type => NULL,
                p_search_term => NULL
            )) WHERE id = ?
            """;

        return jdbcTemplate.query(sql, customerRowMapper(), tenantId, id)
            .stream()
            .findFirst();
    }

    @Override
    public Optional<Customer> findByCode(String tenantId, String customerCode) {
        var call = callFactory.forFunction(PKG_NAME, "GET_CUSTOMER_BY_CODE")
            .returningResultSet("RETURN", customerRowMapper());

        Map<String, Object> params = Map.of(
            "P_TENANT_ID", tenantId,
            "P_CODE", customerCode
        );

        try {
            var result = call.execute(params);
            @SuppressWarnings("unchecked")
            var customers = (List<Customer>) result.get("RETURN");
            return customers.stream().findFirst();
        } catch (Exception e) {
            // Log for debugging instead of silently swallowing
            java.util.logging.Logger.getLogger(OracleCustomerRepository.class.getName())
                .log(java.util.logging.Level.SEVERE, "Error finding customer by code: " + customerCode, e);
            throw new RuntimeException("Error finding customer by code", e);
        }
    }

    @Override
    public Stream<Customer> findByFilter(CustomerFilter filter) {
        var sql = """
            SELECT * FROM TABLE(customer_pkg.get_customers_by_filter(
                p_tenant_id => ?,
                p_active => ?,
                p_customer_type => ?,
                p_search_term => ?
            ))
            """;

        return jdbcTemplate.queryForStream(
            sql,
            customerRowMapper(),
            filter.tenantId(),
            filter.active().map(a -> a ? 1 : 0).orElse(null),
            filter.customerType().orElse(null),
            filter.searchTerm().orElse(null)
        );
    }

    @Override
    public long count(String tenantId, Boolean active) {
        var call = callFactory.forFunction(PKG_NAME, "COUNT_CUSTOMERS")
            .declareParameters(
                new SqlOutParameter("RETURN", Types.NUMERIC),
                new SqlParameter("P_TENANT_ID", Types.VARCHAR),
                new SqlParameter("P_ACTIVE", Types.NUMERIC)
            );

        Map<String, Object> params = new HashMap<>();
        params.put("P_TENANT_ID", tenantId);
        params.put("P_ACTIVE", active != null ? (active ? 1 : 0) : null);

        var result = call.execute(params);
        return ((Number) result.get("RETURN")).longValue();
    }

    @Override
    public Either<ValidationResult, Customer> update(Customer customer, String user) {
        var call = callFactory.forProcedure(PKG_NAME, "UPDATE_CUSTOMER")
            .declareParameters(
                new SqlParameter("P_CUSTOMER", Types.STRUCT, "CUSTOMER_T"),
                new SqlParameter("P_USER", Types.VARCHAR),
                new SqlOutParameter("P_VALIDATION", Types.STRUCT, "VALIDATION_RESULT_T")
            );

        Map<String, Object> params = new HashMap<>();
        params.put("P_CUSTOMER", toOracleStruct(customer));
        params.put("P_USER", user);

        var result = call.execute(params);
        var validation = mapToValidationResult(result.get("P_VALIDATION"));

        if (validation.valid()) {
            return Either.right(customer);
        }
        return Either.left(validation);
    }

    @Override
    public Try<Void> setActive(String tenantId, Long id, boolean active, String user) {
        return Try.run(() -> {
            var call = callFactory.forProcedure(PKG_NAME, "SET_CUSTOMER_ACTIVE");

            Map<String, Object> params = Map.of(
                "P_TENANT_ID", tenantId,
                "P_ID", id,
                "P_ACTIVE", active ? 1 : 0,
                "P_USER", user
            );

            call.execute(params);
        });
    }

    @Override
    public List<ValidationResult> validate(Customer customer) {
        var results = new ArrayList<ValidationResult>();

        if (customer.tenantId() == null || customer.tenantId().isBlank()) {
            results.add(ValidationResult.error("REQUIRED", "Tenant ID is required", "tenantId"));
        }
        if (customer.customerCode() == null || customer.customerCode().isBlank()) {
            results.add(ValidationResult.error("REQUIRED", "Customer code is required", "customerCode"));
        }
        if (customer.name() == null || customer.name().isBlank()) {
            results.add(ValidationResult.error("REQUIRED", "Name is required", "name"));
        }

        customer.email().ifPresent(email -> {
            var emailResult = validateEmail(email);
            if (!emailResult.valid()) {
                results.add(emailResult);
            }
        });

        customer.taxId().ifPresent(taxId -> {
            var taxResult = validateTaxId(taxId, customer.customerType());
            if (!taxResult.valid()) {
                results.add(taxResult);
            }
        });

        return results.isEmpty() ? List.of(ValidationResult.success()) : results;
    }

    @Override
    public ValidationResult validateTaxId(String taxId, String customerType) {
        // Call Oracle function for Brazilian CPF/CNPJ validation
        var call = callFactory.forFunction(PKG_NAME, "VALIDATE_TAX_ID")
            .declareParameters(
                new SqlOutParameter("RETURN", Types.STRUCT, "VALIDATION_RESULT_T"),
                new SqlParameter("P_TAX_ID", Types.VARCHAR),
                new SqlParameter("P_CUSTOMER_TYPE", Types.VARCHAR)
            );

        try {
            Map<String, Object> params = new HashMap<>();
            params.put("P_TAX_ID", taxId);
            params.put("P_CUSTOMER_TYPE", customerType); // May be null
            var result = call.execute(params);
            return mapToValidationResult(result.get("RETURN"));
        } catch (Exception e) {
            return ValidationResult.error("VALIDATION_ERROR", e.getMessage(), "taxId");
        }
    }

    @Override
    public ValidationResult validateEmail(String email) {
        if (email == null || email.isBlank()) {
            return ValidationResult.success();
        }
        if (!email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$")) {
            return ValidationResult.error("INVALID_EMAIL", "Invalid email format", "email");
        }
        return ValidationResult.success();
    }

    // ========================================================================
    // PRIVATE HELPERS
    // ========================================================================

    private RowMapper<Customer> customerRowMapper() {
        return (rs, rowNum) -> mapResultSetToCustomer(rs);
    }

    private Customer mapResultSetToCustomer(ResultSet rs) throws SQLException {
        return new Customer(
            Optional.ofNullable(rs.getObject("ID", Long.class)),
            rs.getString("TENANT_ID"),
            rs.getString("CUSTOMER_CODE"),
            rs.getString("CUSTOMER_TYPE"),
            rs.getString("NAME"),
            Optional.ofNullable(rs.getString("TRADE_NAME")),
            Optional.ofNullable(rs.getString("TAX_ID")),
            Optional.ofNullable(rs.getString("EMAIL")),
            Optional.ofNullable(rs.getString("PHONE")),
            Optional.ofNullable(rs.getString("ADDRESS_STREET")),
            Optional.ofNullable(rs.getString("ADDRESS_CITY")),
            Optional.ofNullable(rs.getString("ADDRESS_STATE")),
            Optional.ofNullable(rs.getString("ADDRESS_ZIP")),
            Optional.ofNullable(rs.getString("ADDRESS_COUNTRY")),
            rs.getInt("ACTIVE") == 1,
            Optional.ofNullable(rs.getTimestamp("CREATED_AT")).map(Timestamp::toLocalDateTime),
            Optional.ofNullable(rs.getTimestamp("UPDATED_AT")).map(Timestamp::toLocalDateTime),
            Optional.ofNullable(rs.getString("CREATED_BY")),
            Optional.ofNullable(rs.getString("UPDATED_BY"))
        );
    }

    private Map<String, Object> toOracleStruct(Customer customer) {
        Map<String, Object> struct = new HashMap<>();
        customer.id().ifPresent(id -> struct.put("ID", id));
        struct.put("TENANT_ID", customer.tenantId());
        struct.put("CUSTOMER_CODE", customer.customerCode());
        struct.put("CUSTOMER_TYPE", customer.customerType());
        struct.put("NAME", customer.name());
        customer.tradeName().ifPresent(t -> struct.put("TRADE_NAME", t));
        customer.taxId().ifPresent(t -> struct.put("TAX_ID", t));
        customer.email().ifPresent(e -> struct.put("EMAIL", e));
        customer.phone().ifPresent(p -> struct.put("PHONE", p));
        customer.addressStreet().ifPresent(a -> struct.put("ADDRESS_STREET", a));
        customer.addressCity().ifPresent(c -> struct.put("ADDRESS_CITY", c));
        customer.addressState().ifPresent(s -> struct.put("ADDRESS_STATE", s));
        customer.addressZip().ifPresent(z -> struct.put("ADDRESS_ZIP", z));
        customer.addressCountry().ifPresent(c -> struct.put("ADDRESS_COUNTRY", c));
        struct.put("ACTIVE", customer.active() ? 1 : 0);
        return struct;
    }

    private TransformMetadata mapToTransformMetadata(Object struct) {
        if (struct instanceof Map<?, ?> map) {
            var source = (String) map.get("SOURCE_SYSTEM");
            var timestampObj = map.get("TRANSFORM_TIMESTAMP");
            var timestamp = timestampObj instanceof Timestamp ts
                ? ts.toLocalDateTime()
                : java.time.LocalDateTime.now();
            var version = (String) map.get("TRANSFORM_VERSION");
            var recordCount = map.get("RECORD_COUNT");
            var successCount = map.get("SUCCESS_COUNT");
            var errorCount = map.get("ERROR_COUNT");
            
            return new TransformMetadata(
                source != null ? source : "UNKNOWN",
                timestamp,
                version != null ? version : "1.0",
                recordCount instanceof Number n ? n.longValue() : 0L,
                successCount instanceof Number n ? n.longValue() : 0L,
                errorCount instanceof Number n ? n.longValue() : 0L
            );
        }
        return TransformMetadata.create("UNKNOWN");
    }

    private ValidationResult mapToValidationResult(Object struct) {
        if (struct instanceof Map<?, ?> map) {
            var isValidObj = map.get("IS_VALID");
            var isValid = isValidObj instanceof Number n && n.intValue() == 1;
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
