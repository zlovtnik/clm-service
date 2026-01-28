package com.gprintex.clm.service;

import com.gprintex.clm.config.ClmProperties;
import com.gprintex.clm.config.SimpleJdbcCallFactory;
import com.gprintex.clm.domain.TransformMetadata;
import com.gprintex.clm.domain.ValidationResult;
import io.vavr.control.Try;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlOutParameter;
import org.springframework.jdbc.core.SqlParameter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Clob;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;

/**
 * ETL Service for staging, transformation, and loading operations.
 * Calls etl_pkg stored procedures.
 */
@Service
public class EtlService {

    private static final String PKG_NAME = "ETL_PKG";

    private final JdbcTemplate jdbcTemplate;
    private final SimpleJdbcCallFactory callFactory;
    private final ClmProperties properties;

    public EtlService(
        JdbcTemplate jdbcTemplate,
        SimpleJdbcCallFactory callFactory,
        ClmProperties properties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.callFactory = callFactory;
        this.properties = properties;
    }

    // ========================================================================
    // SESSION MANAGEMENT
    // ========================================================================

    /**
     * Create a new ETL staging session.
     */
    @Transactional
    public String createSession(String sourceSystem, String entityType) {
        var call = callFactory.forProcedure(PKG_NAME, "CREATE_STAGING_SESSION")
            .declareParameters(
                new SqlOutParameter("P_SESSION_ID", Types.VARCHAR),
                new SqlParameter("P_SOURCE_SYSTEM", Types.VARCHAR),
                new SqlParameter("P_ENTITY_TYPE", Types.VARCHAR),
                new SqlParameter("P_USER", Types.VARCHAR)
            );

        Map<String, Object> params = Map.of(
            "P_SOURCE_SYSTEM", sourceSystem,
            "P_ENTITY_TYPE", entityType,
            "P_USER", "SYSTEM"
        );

        var result = call.execute(params);
        return (String) result.get("P_SESSION_ID");
    }

    /**
     * Get session status.
     */
    @Transactional(readOnly = true)
    public String getSessionStatus(String sessionId) {
        var call = callFactory.forFunction(PKG_NAME, "GET_SESSION_STATUS");
        var result = call.execute(Map.of("P_SESSION_ID", sessionId));
        return (String) result.get("RETURN");
    }

    /**
     * Complete a session successfully.
     */
    @Transactional
    public void completeSession(String sessionId) {
        jdbcTemplate.update(
            "UPDATE etl_sessions SET status = 'COMPLETED', completed_at = SYSTIMESTAMP WHERE session_id = ?",
            sessionId
        );
    }

    /**
     * Mark session as failed.
     */
    @Transactional
    public void failSession(String sessionId, String errorMessage) {
        // Use Jackson for proper JSON escaping of all control characters
        String safeErrorMessage;
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String jsonEscaped = mapper.writeValueAsString(errorMessage != null ? errorMessage : "Unknown error");
            // Remove surrounding quotes that writeValueAsString adds for strings
            safeErrorMessage = jsonEscaped.substring(1, jsonEscaped.length() - 1);
        } catch (Exception e) {
            safeErrorMessage = "Error message encoding failed";
        }
        jdbcTemplate.update(
            "UPDATE etl_sessions SET status = 'FAILED', completed_at = SYSTIMESTAMP, " +
            "metadata = JSON_MERGEPATCH(NVL(metadata, '{}'), JSON_OBJECT('error' VALUE ?)) WHERE session_id = ?",
            safeErrorMessage, sessionId
        );
    }

    // ========================================================================
    // DATA LOADING
    // ========================================================================

    /**
     * Load records to staging table.
     */
    @Transactional
    public void loadToStaging(String sessionId, List<?> records) {
        var call = callFactory.forProcedure(PKG_NAME, "LOAD_TO_STAGING")
            .declareParameters(
                new SqlParameter("P_SESSION_ID", Types.VARCHAR),
                new SqlParameter("P_DATA", Types.CLOB),
                new SqlParameter("P_FORMAT", Types.VARCHAR)
            );

        // Convert records to JSON
        var json = toJson(records);

        Map<String, Object> params = Map.of(
            "P_SESSION_ID", sessionId,
            "P_DATA", json,
            "P_FORMAT", "JSON"
        );

        call.execute(params);
    }

    // ========================================================================
    // TRANSFORMATION
    // ========================================================================

    /**
     * Transform staged contracts.
     */
    @Transactional
    public TransformMetadata transformContracts(String sessionId) {
        return transformContracts(sessionId, null);
    }

    /**
     * Transform staged contracts with custom rules.
     */
    @Transactional
    public TransformMetadata transformContracts(String sessionId, String transformationRules) {
        var call = callFactory.forProcedure(PKG_NAME, "TRANSFORM_CONTRACTS")
            .declareParameters(
                new SqlParameter("P_SESSION_ID", Types.VARCHAR),
                new SqlParameter("P_TRANSFORMATION_RULES", Types.CLOB),
                new SqlOutParameter("P_RESULTS", Types.STRUCT, "TRANSFORM_METADATA_T")
            );

        Map<String, Object> params = new HashMap<>();
        params.put("P_SESSION_ID", sessionId);
        params.put("P_TRANSFORMATION_RULES", transformationRules);

        var result = call.execute(params);
        return mapToTransformMetadata(result.get("P_RESULTS"));
    }

    /**
     * Transform staged customers.
     */
    @Transactional
    public TransformMetadata transformCustomers(String sessionId) {
        var call = callFactory.forProcedure(PKG_NAME, "TRANSFORM_CUSTOMERS")
            .declareParameters(
                new SqlParameter("P_SESSION_ID", Types.VARCHAR),
                new SqlParameter("P_TRANSFORMATION_RULES", Types.CLOB),
                new SqlOutParameter("P_RESULTS", Types.STRUCT, "TRANSFORM_METADATA_T")
            );

        Map<String, Object> params = new HashMap<>();
        params.put("P_SESSION_ID", sessionId);
        params.put("P_TRANSFORMATION_RULES", null);

        var result = call.execute(params);
        return mapToTransformMetadata(result.get("P_RESULTS"));
    }

    // ========================================================================
    // VALIDATION
    // ========================================================================

    /**
     * Validate staging data - returns metadata with validation results.
     */
    @Transactional
    public TransformMetadata validateStaging(String sessionId) {
        // First run validation (populates status on staging records)
        var call = callFactory.forProcedure(PKG_NAME, "APPLY_VALIDATION_RESULTS")
            .declareParameters(
                new SqlParameter("P_SESSION_ID", Types.VARCHAR)
            );

        call.execute(Map.of("P_SESSION_ID", sessionId));

        // Get validation summary
        var sql = """
            SELECT 
                COUNT(*) as record_count,
                COUNT(CASE WHEN status = 'VALIDATED' THEN 1 END) as success_count,
                COUNT(CASE WHEN status = 'FAILED' THEN 1 END) as error_count
            FROM etl_staging
            WHERE session_id = ?
            """;

        return jdbcTemplate.queryForObject(sql, (rs, rowNum) ->
            new TransformMetadata(
                "VALIDATION",
                LocalDateTime.now(),
                "1.0",
                rs.getLong("record_count"),
                rs.getLong("success_count"),
                rs.getLong("error_count")
            ), sessionId);
    }

    /**
     * Get validation errors for a session.
     */
    @Transactional(readOnly = true)
    public List<ValidationResult> getValidationErrors(String sessionId) {
        var sql = """
            SELECT error_message FROM etl_staging
            WHERE session_id = ? AND status = 'FAILED'
            """;

        return jdbcTemplate.query(sql, (rs, rowNum) ->
            ValidationResult.error("VALIDATION_FAILED", rs.getString("error_message")),
            sessionId
        );
    }

    /**
     * Handle validation errors.
     */
    @Transactional
    public void handleValidationErrors(String sessionId, TransformMetadata metadata) {
        // Log errors and optionally skip failed records
        jdbcTemplate.update(
            "UPDATE etl_staging SET status = 'SKIPPED' WHERE session_id = ? AND status = 'FAILED'",
            sessionId
        );
    }

    // ========================================================================
    // PROMOTION
    // ========================================================================

    /**
     * Promote validated contracts from staging to target.
     */
    @Transactional
    public TransformMetadata promoteContracts(String sessionId) {
        return promoteFromStaging(sessionId, "CONTRACTS");
    }

    /**
     * Promote validated customers from staging to target.
     */
    @Transactional
    public TransformMetadata promoteCustomers(String sessionId) {
        return promoteFromStaging(sessionId, "CUSTOMERS");
    }

    /**
     * Generic promotion from staging.
     */
    @Transactional
    public TransformMetadata promoteFromStaging(String sessionId, String targetTable) {
        var call = callFactory.forProcedure(PKG_NAME, "PROMOTE_FROM_STAGING")
            .declareParameters(
                new SqlParameter("P_SESSION_ID", Types.VARCHAR),
                new SqlParameter("P_TARGET_TABLE", Types.VARCHAR),
                new SqlParameter("P_USER", Types.VARCHAR),
                new SqlOutParameter("P_RESULTS", Types.STRUCT, "TRANSFORM_METADATA_T")
            );

        Map<String, Object> params = Map.of(
            "P_SESSION_ID", sessionId,
            "P_TARGET_TABLE", targetTable,
            "P_USER", "SYSTEM"
        );

        var result = call.execute(params);
        return mapToTransformMetadata(result.get("P_RESULTS"));
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private String toJson(List<?> records) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.findAndRegisterModules();
            return mapper.writeValueAsString(records);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize records to JSON", e);
        }
    }

    private TransformMetadata mapToTransformMetadata(Object struct) {
        if (struct instanceof Map<?, ?> map) {
            var source = (String) map.get("SOURCE_SYSTEM");
            var timestampObj = map.get("TRANSFORM_TIMESTAMP");
            var timestamp = timestampObj instanceof Timestamp ts
                ? ts.toLocalDateTime()
                : LocalDateTime.now();
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
}
