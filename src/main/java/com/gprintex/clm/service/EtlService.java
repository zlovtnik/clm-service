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
     * Get detailed session info including counts by status.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getSessionInfo(String sessionId) {
        var sql = """
            SELECT s.*,
                (SELECT COUNT(*) FROM etl_staging WHERE session_id = s.session_id AND status = 'PENDING') as pending_count,
                (SELECT COUNT(*) FROM etl_staging WHERE session_id = s.session_id AND status = 'TRANSFORMED') as transformed_count,
                (SELECT COUNT(*) FROM etl_staging WHERE session_id = s.session_id AND status = 'VALIDATED') as validated_count,
                (SELECT COUNT(*) FROM etl_staging WHERE session_id = s.session_id AND status = 'LOADED') as loaded_count,
                (SELECT COUNT(*) FROM etl_staging WHERE session_id = s.session_id AND status = 'FAILED') as failed_count
            FROM etl_sessions s
            WHERE s.session_id = ?
            """;

        return jdbcTemplate.queryForMap(sql, sessionId);
    }

    /**
     * Get all active ETL sessions.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getActiveSessions() {
        var sql = """
            SELECT session_id, source_system, entity_type, status, 
                   record_count, success_count, error_count, started_at, started_by
            FROM etl_sessions 
            WHERE status IN ('ACTIVE', 'PROCESSING')
            ORDER BY started_at DESC
            """;

        return jdbcTemplate.queryForList(sql);
    }

    /**
     * Get session audit trail.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getSessionAuditTrail(String sessionId) {
        var sql = """
            SELECT audit_id, entity_type, entity_id, tenant_id, 
                   transform_type, transform_rule, transform_timestamp, transformed_by
            FROM transform_audit
            WHERE session_id = ?
            ORDER BY transform_timestamp
            """;

        return jdbcTemplate.queryForList(sql, sessionId);
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

    /**
     * Rollback session - removes all staged data and marks session as rolled back.
     */
    @Transactional
    public void rollbackSession(String sessionId) {
        var call = callFactory.forProcedure(PKG_NAME, "ROLLBACK_SESSION")
            .declareParameters(
                new SqlParameter("P_SESSION_ID", Types.VARCHAR)
            );

        call.execute(Map.of("P_SESSION_ID", sessionId));
    }

    /**
     * Cleanup old sessions based on retention days.
     * Returns the number of sessions deleted.
     * @param retentionDays must be > 0 and <= 3650
     * @throws IllegalArgumentException if retentionDays is out of valid range
     */
    @Transactional
    public long cleanupOldSessions(int retentionDays) {
        // Validate retention days to avoid accidental data loss
        if (retentionDays <= 0) {
            throw new IllegalArgumentException("retentionDays must be greater than 0");
        }
        if (retentionDays > 3650) {
            throw new IllegalArgumentException("retentionDays must not exceed 3650 (10 years)");
        }
        
        var call = callFactory.forProcedure(PKG_NAME, "CLEANUP_OLD_SESSIONS")
            .declareParameters(
                new SqlParameter("P_RETENTION_DAYS", Types.NUMERIC),
                new SqlOutParameter("P_DELETED_COUNT", Types.NUMERIC)
            );

        var result = call.execute(Map.of("P_RETENTION_DAYS", retentionDays));
        var deletedCount = result.get("P_DELETED_COUNT");
        return deletedCount instanceof Number n ? n.longValue() : 0L;
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

    /**
     * Load a single record to staging and return its sequence number.
     */
    @Transactional
    public long loadRecordToStaging(String sessionId, String rawData) {
        var call = callFactory.forProcedure(PKG_NAME, "LOAD_RECORD_TO_STAGING")
            .declareParameters(
                new SqlParameter("P_SESSION_ID", Types.VARCHAR),
                new SqlParameter("P_RAW_DATA", Types.CLOB),
                new SqlOutParameter("P_SEQ_NUM", Types.NUMERIC)
            );

        Map<String, Object> params = new HashMap<>();
        params.put("P_SESSION_ID", sessionId);
        params.put("P_RAW_DATA", rawData);

        var result = call.execute(params);
        var seqNum = result.get("P_SEQ_NUM");
        return seqNum instanceof Number n ? n.longValue() : 0L;
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
        return transformCustomers(sessionId, null);
    }

    /**
     * Transform staged customers with custom rules.
     */
    @Transactional
    public TransformMetadata transformCustomers(String sessionId, String transformationRules) {
        var call = callFactory.forProcedure(PKG_NAME, "TRANSFORM_CUSTOMERS")
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
     * Apply business rules to staging data.
     */
    @Transactional
    public void applyBusinessRules(String sessionId, String ruleSet) {
        var call = callFactory.forProcedure(PKG_NAME, "APPLY_BUSINESS_RULES")
            .declareParameters(
                new SqlParameter("P_SESSION_ID", Types.VARCHAR),
                new SqlParameter("P_RULE_SET", Types.VARCHAR)
            );

        // Use mutable map to handle nullable ruleSet
        Map<String, Object> params = new HashMap<>();
        params.put("P_SESSION_ID", sessionId);
        params.put("P_RULE_SET", ruleSet != null ? ruleSet : "DEFAULT");

        call.execute(params);
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
     * Get validation summary for a session.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getValidationSummary(String sessionId) {
        var sql = """
            SELECT 
                COUNT(*) as total_count,
                COUNT(CASE WHEN status = 'PENDING' THEN 1 END) as pending_count,
                COUNT(CASE WHEN status = 'TRANSFORMED' THEN 1 END) as transformed_count,
                COUNT(CASE WHEN status = 'VALIDATED' THEN 1 END) as validated_count,
                COUNT(CASE WHEN status = 'FAILED' THEN 1 END) as failed_count,
                COUNT(CASE WHEN status = 'SKIPPED' THEN 1 END) as skipped_count
            FROM etl_staging
            WHERE session_id = ?
            """;

        return jdbcTemplate.queryForMap(sql, sessionId);
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
        return promoteContracts(sessionId, "SYSTEM");
    }

    /**
     * Promote validated contracts from staging to target with specified user.
     */
    @Transactional
    public TransformMetadata promoteContracts(String sessionId, String user) {
        var call = callFactory.forProcedure(PKG_NAME, "PROMOTE_CONTRACTS")
            .declareParameters(
                new SqlParameter("P_SESSION_ID", Types.VARCHAR),
                new SqlParameter("P_USER", Types.VARCHAR),
                new SqlOutParameter("P_RESULTS", Types.STRUCT, "TRANSFORM_METADATA_T")
            );

        // Guard against null user - use mutable map
        String safeUser = java.util.Objects.requireNonNullElse(user, "SYSTEM");
        Map<String, Object> params = new HashMap<>();
        params.put("P_SESSION_ID", sessionId);
        params.put("P_USER", safeUser);

        var result = call.execute(params);
        return mapToTransformMetadata(result.get("P_RESULTS"));
    }

    /**
     * Promote validated customers from staging to target.
     */
    @Transactional
    public TransformMetadata promoteCustomers(String sessionId) {
        return promoteCustomers(sessionId, "SYSTEM");
    }

    /**
     * Promote validated customers from staging to target with specified user.
     */
    @Transactional
    public TransformMetadata promoteCustomers(String sessionId, String user) {
        var call = callFactory.forProcedure(PKG_NAME, "PROMOTE_CUSTOMERS")
            .declareParameters(
                new SqlParameter("P_SESSION_ID", Types.VARCHAR),
                new SqlParameter("P_USER", Types.VARCHAR),
                new SqlOutParameter("P_RESULTS", Types.STRUCT, "TRANSFORM_METADATA_T")
            );

        // Guard against null user - use mutable map  
        String safeUser = java.util.Objects.requireNonNullElse(user, "SYSTEM");
        Map<String, Object> params = new HashMap<>();
        params.put("P_SESSION_ID", sessionId);
        params.put("P_USER", safeUser);

        var result = call.execute(params);
        return mapToTransformMetadata(result.get("P_RESULTS"));
    }

    /**
     * Generic promotion from staging.
     */
    @Transactional
    public TransformMetadata promoteFromStaging(String sessionId, String targetTable, String user) {
        var call = callFactory.forProcedure(PKG_NAME, "PROMOTE_FROM_STAGING")
            .declareParameters(
                new SqlParameter("P_SESSION_ID", Types.VARCHAR),
                new SqlParameter("P_TARGET_TABLE", Types.VARCHAR),
                new SqlParameter("P_USER", Types.VARCHAR),
                new SqlOutParameter("P_RESULTS", Types.STRUCT, "TRANSFORM_METADATA_T")
            );

        String safeUser = java.util.Objects.requireNonNullElse(user, "SYSTEM");
        Map<String, Object> params = new HashMap<>();
        params.put("P_SESSION_ID", sessionId);
        params.put("P_TARGET_TABLE", targetTable);
        params.put("P_USER", safeUser);

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
