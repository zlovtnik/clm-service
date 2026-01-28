package com.gprintex.clm.service;

import com.gprintex.clm.config.ClmProperties;
import com.gprintex.clm.config.SimpleJdbcCallFactory;
import com.gprintex.clm.domain.IntegrationMessage;
import com.gprintex.clm.domain.TransformMetadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameter;
import org.springframework.jdbc.core.SqlOutParameter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Integration service for EIP message operations.
 * Calls integration_pkg stored procedures.
 */
@Service
public class IntegrationService {

    private static final String PKG_NAME = "INTEGRATION_PKG";

    private final JdbcTemplate jdbcTemplate;
    private final SimpleJdbcCallFactory callFactory;
    private final ClmProperties properties;

    public IntegrationService(
        JdbcTemplate jdbcTemplate,
        SimpleJdbcCallFactory callFactory,
        ClmProperties properties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.callFactory = callFactory;
        this.properties = properties;
    }

    // ========================================================================
    // MESSAGE ROUTING
    // ========================================================================

    /**
     * Route a message to appropriate destination.
     */
    @Transactional
    public String routeMessage(IntegrationMessage message) {
        var call = callFactory.forProcedure(PKG_NAME, "ROUTE_MESSAGE")
            .declareParameters(
                new SqlParameter("P_MESSAGE_ID", Types.VARCHAR),
                new SqlParameter("P_MESSAGE_TYPE", Types.VARCHAR),
                new SqlParameter("P_PAYLOAD", Types.CLOB),
                new SqlParameter("P_ROUTING_KEY", Types.VARCHAR),
                new SqlParameter("P_CORRELATION_ID", Types.VARCHAR),
                new SqlParameter("P_SOURCE_SYSTEM", Types.VARCHAR),
                new SqlOutParameter("P_DESTINATION", Types.VARCHAR)
            );

        Map<String, Object> params = new HashMap<>();
        params.put("P_MESSAGE_ID", message.messageId());
        params.put("P_MESSAGE_TYPE", message.messageType());
        params.put("P_PAYLOAD", message.payload());
        params.put("P_ROUTING_KEY", message.routingKey().orElse(null));
        params.put("P_CORRELATION_ID", message.correlationId().orElse(null));
        params.put("P_SOURCE_SYSTEM", message.sourceSystem().orElse(null));

        var result = call.execute(params);
        return (String) result.get("P_DESTINATION");
    }

    /**
     * Get destination for message type.
     */
    @Transactional(readOnly = true)
    public String getDestination(String messageType, String routingKey) {
        var call = callFactory.forFunction(PKG_NAME, "GET_DESTINATION")
            .declareParameters(
                new SqlOutParameter("RETURN", Types.VARCHAR),
                new SqlParameter("P_MESSAGE_TYPE", Types.VARCHAR),
                new SqlParameter("P_ROUTING_KEY", Types.VARCHAR)
            );

        var result = call.execute(Map.of(
            "P_MESSAGE_TYPE", messageType,
            "P_ROUTING_KEY", routingKey != null ? routingKey : ""
        ));

        return (String) result.get("RETURN");
    }

    // ========================================================================
    // DEDUPLICATION
    // ========================================================================

    /**
     * Check if message is a duplicate.
     */
    @Transactional(readOnly = true)
    public boolean isDuplicate(String messageId) {
        var call = callFactory.forFunction(PKG_NAME, "IS_DUPLICATE_MESSAGE")
            .declareParameters(
                new SqlOutParameter("RETURN", Types.NUMERIC),
                new SqlParameter("P_MESSAGE_ID", Types.VARCHAR),
                new SqlParameter("P_DEDUP_WINDOW_HOURS", Types.NUMERIC)
            );

        var result = call.execute(Map.of(
            "P_MESSAGE_ID", messageId,
            "P_DEDUP_WINDOW_HOURS", properties.integration().dedupWindowHours()
        ));

        var returnVal = result.get("RETURN");
        return returnVal instanceof Number n && n.intValue() == 1;
    }

    /**
     * Check duplicate by content hash.
     */
    @Transactional(readOnly = true)
    public boolean isDuplicateByHash(String messageHash, String tenantId) {
        var call = callFactory.forFunction(PKG_NAME, "IS_DUPLICATE_BY_HASH")
            .declareParameters(
                new SqlOutParameter("RETURN", Types.NUMERIC),
                new SqlParameter("P_MESSAGE_HASH", Types.VARCHAR),
                new SqlParameter("P_TENANT_ID", Types.VARCHAR),
                new SqlParameter("P_DEDUP_WINDOW_HOURS", Types.NUMERIC)
            );

        var result = call.execute(Map.of(
            "P_MESSAGE_HASH", messageHash,
            "P_TENANT_ID", tenantId,
            "P_DEDUP_WINDOW_HOURS", properties.integration().dedupWindowHours()
        ));

        var returnVal = result.get("RETURN");
        return returnVal instanceof Number n && n.intValue() == 1;
    }

    /**
     * Mark message as processed.
     */
    @Transactional
    public void markProcessed(String messageId, String correlationId) {
        var call = callFactory.forProcedure(PKG_NAME, "MARK_MESSAGE_PROCESSED")
            .declareParameters(
                new SqlParameter("P_MESSAGE_ID", Types.VARCHAR),
                new SqlParameter("P_CORRELATION_ID", Types.VARCHAR),
                new SqlParameter("P_METADATA", Types.CLOB)
            );

        Map<String, Object> params = new HashMap<>();
        params.put("P_MESSAGE_ID", messageId);
        params.put("P_CORRELATION_ID", correlationId);
        params.put("P_METADATA", null);

        call.execute(params);
    }

    // ========================================================================
    // AGGREGATION
    // ========================================================================

    /**
     * Start aggregation for correlation ID.
     */
    @Transactional
    public void startAggregation(String correlationId, String aggregationKey, Integer expectedCount) {
        var call = callFactory.forProcedure(PKG_NAME, "START_AGGREGATION")
            .declareParameters(
                new SqlParameter("P_CORRELATION_ID", Types.VARCHAR),
                new SqlParameter("P_AGGREGATION_KEY", Types.VARCHAR),
                new SqlParameter("P_EXPECTED_COUNT", Types.NUMERIC),
                new SqlParameter("P_TIMEOUT_SECONDS", Types.NUMERIC)
            );

        Map<String, Object> params = new HashMap<>();
        params.put("P_CORRELATION_ID", correlationId);
        params.put("P_AGGREGATION_KEY", aggregationKey);
        params.put("P_EXPECTED_COUNT", expectedCount);
        params.put("P_TIMEOUT_SECONDS", properties.integration().aggregationTimeoutSeconds());

        call.execute(params);
    }

    /**
     * Add message to aggregation.
     */
    @Transactional
    public void addToAggregation(String correlationId, String aggregationKey, String message) {
        var call = callFactory.forProcedure(PKG_NAME, "ADD_TO_AGGREGATION")
            .declareParameters(
                new SqlParameter("P_CORRELATION_ID", Types.VARCHAR),
                new SqlParameter("P_AGGREGATION_KEY", Types.VARCHAR),
                new SqlParameter("P_MESSAGE", Types.CLOB)
            );

        call.execute(Map.of(
            "P_CORRELATION_ID", correlationId,
            "P_AGGREGATION_KEY", aggregationKey,
            "P_MESSAGE", message
        ));
    }

    /**
     * Check if aggregation is complete.
     */
    @Transactional(readOnly = true)
    public boolean isAggregationComplete(String correlationId, String aggregationKey) {
        var call = callFactory.forFunction(PKG_NAME, "IS_AGGREGATION_COMPLETE")
            .declareParameters(
                new SqlOutParameter("RETURN", Types.NUMERIC),
                new SqlParameter("P_CORRELATION_ID", Types.VARCHAR),
                new SqlParameter("P_AGGREGATION_KEY", Types.VARCHAR)
            );

        var result = call.execute(Map.of(
            "P_CORRELATION_ID", correlationId,
            "P_AGGREGATION_KEY", aggregationKey
        ));

        var returnVal = result.get("RETURN");
        return returnVal instanceof Number n && n.intValue() == 1;
    }

    /**
     * Get aggregated result.
     */
    @Transactional
    public String getAggregatedResult(String correlationId, String aggregationKey) {
        var call = callFactory.forProcedure(PKG_NAME, "GET_AGGREGATED_RESULT")
            .declareParameters(
                new SqlParameter("P_CORRELATION_ID", Types.VARCHAR),
                new SqlParameter("P_AGGREGATION_KEY", Types.VARCHAR),
                new SqlOutParameter("P_AGGREGATED_RESULT", Types.CLOB)
            );

        var result = call.execute(Map.of(
            "P_CORRELATION_ID", correlationId,
            "P_AGGREGATION_KEY", aggregationKey
        ));

        return (String) result.get("P_AGGREGATED_RESULT");
    }

    /**
     * Process aggregated result.
     */
    @Transactional
    public void processAggregatedResult(String correlationId, String aggregationKey) {
        // Complete the aggregation
        var call = callFactory.forProcedure(PKG_NAME, "COMPLETE_AGGREGATION");
        call.execute(Map.of(
            "P_CORRELATION_ID", correlationId,
            "P_AGGREGATION_KEY", aggregationKey
        ));
    }

    /**
     * Process aggregation timeouts.
     */
    @Transactional
    public void processAggregationTimeouts() {
        var call = callFactory.forProcedure(PKG_NAME, "PROCESS_AGGREGATION_TIMEOUTS");
        call.execute(Map.of());
    }

    // ========================================================================
    // RETRY
    // ========================================================================

    /**
     * Get messages pending retry.
     */
    @Transactional(readOnly = true)
    public List<IntegrationMessage> getPendingRetries() {
        var sql = """
            SELECT * FROM integration_messages
            WHERE status = 'PENDING'
              AND next_retry_at <= SYSTIMESTAMP
              AND retry_count < max_retries
            ORDER BY next_retry_at
            FETCH FIRST 100 ROWS ONLY
            """;

        return jdbcTemplate.query(sql, (rs, rowNum) ->
            new IntegrationMessage(
                rs.getString("MESSAGE_ID"),
                Optional.ofNullable(rs.getString("CORRELATION_ID")),
                rs.getString("MESSAGE_TYPE"),
                Optional.ofNullable(rs.getString("SOURCE_SYSTEM")),
                Optional.ofNullable(rs.getString("ROUTING_KEY")),
                Optional.ofNullable(rs.getString("DESTINATION")),
                rs.getString("PAYLOAD"),
                rs.getString("STATUS"),
                rs.getTimestamp("CREATED_AT").toLocalDateTime(),
                Optional.ofNullable(rs.getTimestamp("PROCESSED_AT"))
                    .map(Timestamp::toLocalDateTime),
                rs.getInt("RETRY_COUNT"),
                rs.getInt("MAX_RETRIES"),
                Optional.ofNullable(rs.getTimestamp("NEXT_RETRY_AT"))
                    .map(Timestamp::toLocalDateTime),
                Optional.ofNullable(rs.getString("ERROR_MESSAGE"))
            )
        );
    }

    // ========================================================================
    // NOTIFICATIONS
    // ========================================================================

    /**
     * Notify that ETL is complete.
     */
    public void notifyEtlComplete(String sessionId, TransformMetadata metadata) {
        // Create notification message using Jackson for safe JSON
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var payloadMap = new HashMap<String, Object>();
            payloadMap.put("sessionId", sessionId);
            payloadMap.put("recordCount", metadata.recordCount());
            payloadMap.put("successCount", metadata.successCount());
            payloadMap.put("errorCount", metadata.errorCount());
            
            var message = IntegrationMessage.create(
                UUID.randomUUID().toString(),
                "ETL_COMPLETE",
                mapper.writeValueAsString(payloadMap)
            );

            routeMessage(message);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize ETL notification", e);
        }
    }

    /**
     * Store unknown message type for review.
     */
    @Transactional
    public void storeUnknownMessage(IntegrationMessage message) {
        jdbcTemplate.update(
            "INSERT INTO integration_messages (message_id, message_type, payload, status, created_at) " +
            "VALUES (?, ?, ?, 'DEAD_LETTER', SYSTIMESTAMP)",
            message.messageId(),
            message.messageType(),
            message.payload()
        );
    }
}
