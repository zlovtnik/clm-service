-- =============================================================================
-- MIGRATION 003: CREATE COMPATIBILITY VIEWS
-- Purpose: Bridge old package references to new schema tables
-- Author: GprintEx Team
-- Date: 2026-01-28
-- Note: These views allow existing packages to work with the new schema
-- =============================================================================

-- =============================================================================
-- VIEW: integration_messages
-- Maps to: messages (core message table)
-- Used by: integration_pkg
-- =============================================================================
CREATE OR REPLACE VIEW integration_messages AS
SELECT 
    m.message_id,
    m.correlation_id,
    m.message_type,
    m.source_endpoint AS source_system,
    NULL AS routing_key,  -- No direct mapping, routing is in routing_decisions
    m.target_endpoint AS destination,
    mp.payload,  -- LEFT JOIN instead of scalar subquery (N+1 fix)
    CASE m.status
        WHEN 'CREATED' THEN 'PENDING'
        WHEN 'QUEUED' THEN 'PENDING'
        WHEN 'ROUTING' THEN 'PROCESSING'
        WHEN 'TRANSFORMING' THEN 'PROCESSING'
        WHEN 'VALIDATING' THEN 'PROCESSING'
        WHEN 'PROCESSING' THEN 'PROCESSING'
        WHEN 'AGGREGATING' THEN 'PROCESSING'
        WHEN 'SPLITTING' THEN 'PROCESSING'
        WHEN 'ENRICHING' THEN 'PROCESSING'
        WHEN 'COMPLETED' THEN 'COMPLETED'
        WHEN 'FAILED' THEN 'FAILED'
        WHEN 'DEAD_LETTER' THEN 'DEAD_LETTER'
        WHEN 'ARCHIVED' THEN 'COMPLETED'
        ELSE m.status
    END AS status,
    m.created_at,
    m.completed_at AS processed_at,
    m.retry_count,
    m.max_retries,
    m.next_retry_at,
    NULL AS error_message,  -- Stored in message_state_transitions
    m.tenant_id
FROM messages m
LEFT JOIN message_payloads mp ON mp.message_id = m.message_id;

-- Trigger to handle INSERTs into the view
CREATE OR REPLACE TRIGGER trg_integration_messages_ins
INSTEAD OF INSERT ON integration_messages
FOR EACH ROW
DECLARE
    v_status VARCHAR2(20);
BEGIN
    -- Map status back
    v_status := CASE :NEW.status
        WHEN 'PENDING' THEN 'CREATED'
        WHEN 'PROCESSING' THEN 'PROCESSING'
        WHEN 'COMPLETED' THEN 'COMPLETED'
        WHEN 'FAILED' THEN 'FAILED'
        WHEN 'DEAD_LETTER' THEN 'DEAD_LETTER'
        ELSE 'CREATED'
    END;
    
    -- Insert into messages
    INSERT INTO messages (
        message_id, correlation_id, message_type, source_endpoint,
        target_endpoint, status, created_at, retry_count, max_retries,
        next_retry_at, tenant_id
    ) VALUES (
        :NEW.message_id, :NEW.correlation_id, :NEW.message_type, :NEW.source_system,
        :NEW.destination, v_status, NVL(:NEW.created_at, SYSTIMESTAMP), 
        NVL(:NEW.retry_count, 0), NVL(:NEW.max_retries, 3),
        :NEW.next_retry_at, NVL(:NEW.tenant_id, 'DEFAULT')
    );
    
    -- Insert payload if provided
    IF :NEW.payload IS NOT NULL THEN
        INSERT INTO message_payloads (message_id, payload, created_at)
        VALUES (:NEW.message_id, :NEW.payload, SYSTIMESTAMP);
    END IF;
END;
/

-- Trigger to handle UPDATEs on the view
CREATE OR REPLACE TRIGGER trg_integration_messages_upd
INSTEAD OF UPDATE ON integration_messages
FOR EACH ROW
DECLARE
    v_status VARCHAR2(20);
BEGIN
    v_status := CASE :NEW.status
        WHEN 'PENDING' THEN 'CREATED'
        WHEN 'PROCESSING' THEN 'PROCESSING'
        WHEN 'COMPLETED' THEN 'COMPLETED'
        WHEN 'FAILED' THEN 'FAILED'
        WHEN 'DEAD_LETTER' THEN 'DEAD_LETTER'
        ELSE :NEW.status
    END;
    
    UPDATE messages SET
        status = v_status,
        completed_at = CASE WHEN v_status IN ('COMPLETED', 'FAILED', 'DEAD_LETTER') 
                       THEN SYSTIMESTAMP ELSE completed_at END,
        retry_count = :NEW.retry_count,
        next_retry_at = :NEW.next_retry_at
    WHERE message_id = :OLD.message_id;
    
    -- Handle payload changes: add, update, or remove
    IF :NEW.payload IS NULL AND :OLD.payload IS NOT NULL THEN
        -- Payload removed - delete from message_payloads
        DELETE FROM message_payloads WHERE message_id = :OLD.message_id;
    ELSIF (:NEW.payload IS NOT NULL AND :OLD.payload IS NULL) OR
          (:NEW.payload IS NOT NULL AND :OLD.payload IS NOT NULL AND 
           (DBMS_LOB.GETLENGTH(:NEW.payload) = 0 AND DBMS_LOB.GETLENGTH(:OLD.payload) > 0) OR
           (DBMS_LOB.GETLENGTH(:NEW.payload) > 0 AND DBMS_LOB.GETLENGTH(:OLD.payload) = 0) OR
           (DBMS_LOB.GETLENGTH(:NEW.payload) > 0 AND DBMS_LOB.GETLENGTH(:OLD.payload) > 0 AND
            DBMS_LOB.COMPARE(:NEW.payload, :OLD.payload) != 0)) THEN
        -- Payload added or changed - upsert
        MERGE INTO message_payloads mp
        USING (SELECT :OLD.message_id AS message_id FROM DUAL) src
        ON (mp.message_id = src.message_id)
        WHEN MATCHED THEN
            UPDATE SET payload = :NEW.payload
        WHEN NOT MATCHED THEN
            INSERT (message_id, payload, created_at)
            VALUES (:OLD.message_id, :NEW.payload, SYSTIMESTAMP);
    END IF;
END;
/

COMMENT ON TABLE integration_messages IS 'Compatibility view mapping to messages table';

-- =============================================================================
-- VIEW: message_dedup
-- Maps to: message_deduplication
-- Used by: integration_pkg for deduplication
-- =============================================================================
CREATE OR REPLACE VIEW message_dedup AS
SELECT
    message_id,
    content_hash AS message_hash,
    first_seen_at AS first_seen,
    last_seen_at AS last_seen,
    occurrence_count AS process_count,
    tenant_id,
    message_type
FROM message_deduplication;

-- Trigger to handle INSERTs into the view
-- Note: Dedup expiration uses 24 hours by default. Configure via integration_pkg.c_default_dedup_hours
CREATE OR REPLACE TRIGGER trg_message_dedup_ins
INSTEAD OF INSERT ON message_dedup
FOR EACH ROW
DECLARE
    v_dedup_hours NUMBER := 24;  -- Default, matches integration_pkg.c_default_dedup_hours
BEGIN
    INSERT INTO message_deduplication (
        message_id, content_hash, tenant_id, message_type,
        first_seen_at, last_seen_at, occurrence_count,
        original_message_id, expires_at
    ) VALUES (
        :NEW.message_id, :NEW.message_hash, NVL(:NEW.tenant_id, 'DEFAULT'),
        :NEW.message_type, NVL(:NEW.first_seen, SYSTIMESTAMP),
        NVL(:NEW.last_seen, SYSTIMESTAMP), NVL(:NEW.process_count, 1),
        :NEW.message_id, SYSTIMESTAMP + (v_dedup_hours / 24)  -- v_dedup_hours as interval
    );
END;
/

-- Trigger to handle UPDATEs on the view
CREATE OR REPLACE TRIGGER trg_message_dedup_upd
INSTEAD OF UPDATE ON message_dedup
FOR EACH ROW
DECLARE
    v_dedup_hours NUMBER := 24;  -- Default, matches integration_pkg.c_default_dedup_hours
BEGIN
    UPDATE message_deduplication SET
        last_seen_at = :NEW.last_seen,
        occurrence_count = :NEW.process_count,
        expires_at = SYSTIMESTAMP + (v_dedup_hours / 24)  -- Refresh expiration on update
    WHERE message_id = :OLD.message_id;
END;
/

COMMENT ON TABLE message_dedup IS 'Compatibility view mapping to message_deduplication table';

-- =============================================================================
-- VIEW: message_aggregation
-- Maps to: aggregation_instances
-- Used by: integration_pkg for message aggregation
-- =============================================================================
CREATE OR REPLACE VIEW message_aggregation AS
SELECT
    ai.instance_id,
    ai.correlation_id,
    ad.aggregation_key,  -- LEFT JOIN instead of scalar subquery (N+1 fix)
    ai.expected_count,
    ai.current_count,
    CASE ai.status
        WHEN 'COLLECTING' THEN 'PENDING'
        WHEN 'COMPLETE' THEN 'COMPLETE'
        WHEN 'TIMEOUT' THEN 'TIMEOUT'
        WHEN 'CANCELLED' THEN 'CANCELLED'
        WHEN 'FAILED' THEN 'FAILED'
        ELSE ai.status
    END AS status,
    ai.started_at,
    ai.timeout_at,
    ai.completed_at,
    ai.aggregated_payload AS aggregated_result
FROM aggregation_instances ai
LEFT JOIN aggregation_definitions ad ON ad.aggregation_id = ai.aggregation_id;

-- Trigger to handle INSERTs into the view
CREATE OR REPLACE TRIGGER trg_message_aggregation_ins
INSTEAD OF INSERT ON message_aggregation
FOR EACH ROW
DECLARE
    v_agg_id NUMBER;
    v_instance_id VARCHAR2(100);
BEGIN
    -- Find or create aggregation definition
    BEGIN
        SELECT aggregation_id INTO v_agg_id
        FROM aggregation_definitions
        WHERE aggregation_key = :NEW.aggregation_key;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            INSERT INTO aggregation_definitions (
                aggregation_key, aggregation_name, aggregation_strategy,
                correlation_expression, completion_strategy, timeout_seconds
            ) VALUES (
                :NEW.aggregation_key, :NEW.aggregation_key, 'COLLECT_ALL',
                '$.correlationId', 'SIZE', 300
            ) RETURNING aggregation_id INTO v_agg_id;
    END;
    
    -- Use caller-provided instance_id if present, otherwise auto-generate
    v_instance_id := NVL(:NEW.instance_id, SYS_GUID());
    
    INSERT INTO aggregation_instances (
        instance_id, aggregation_id, correlation_id, status,
        expected_count, current_count, started_at, timeout_at
    ) VALUES (
        v_instance_id, v_agg_id, :NEW.correlation_id, 'COLLECTING',
        :NEW.expected_count, NVL(:NEW.current_count, 0),
        NVL(:NEW.started_at, SYSTIMESTAMP), :NEW.timeout_at
    );
END;
/

-- Trigger to handle UPDATEs on the view
CREATE OR REPLACE TRIGGER trg_message_aggregation_upd
INSTEAD OF UPDATE ON message_aggregation
FOR EACH ROW
DECLARE
    v_status VARCHAR2(20);
BEGIN
    v_status := CASE :NEW.status
        WHEN 'PENDING' THEN 'COLLECTING'
        WHEN 'COMPLETE' THEN 'COMPLETE'
        WHEN 'TIMEOUT' THEN 'TIMEOUT'
        WHEN 'CANCELLED' THEN 'CANCELLED'
        ELSE :NEW.status
    END;
    
    UPDATE aggregation_instances SET
        status = v_status,
        current_count = :NEW.current_count,
        aggregated_payload = :NEW.aggregated_result,
        completed_at = CASE WHEN v_status IN ('COMPLETE', 'TIMEOUT', 'CANCELLED') 
                       THEN SYSTIMESTAMP ELSE completed_at END
    WHERE instance_id = :OLD.instance_id
      AND correlation_id = :OLD.correlation_id;
END;
/

COMMENT ON TABLE message_aggregation IS 'Compatibility view mapping to aggregation_instances table';

-- =============================================================================
-- VIEW: routing_rules
-- Maps to: routing_configs
-- Used by: integration_pkg for message routing
-- =============================================================================
CREATE OR REPLACE VIEW routing_rules AS
SELECT
    config_id AS rule_id,
    config_name AS rule_name,
    COALESCE(namespace_pattern, 'DEFAULT') AS rule_set,
    priority,
    message_type_pattern AS message_type,
    route_expression AS condition_expression,
    default_target AS destination,
    NULL AS transform_template,
    active,
    created_at,
    updated_at,
    created_by,
    updated_by
FROM routing_configs;

-- Trigger to handle INSERTs into the view
CREATE OR REPLACE TRIGGER trg_routing_rules_ins
INSTEAD OF INSERT ON routing_rules
FOR EACH ROW
BEGIN
    INSERT INTO routing_configs (
        config_name, message_type_pattern, namespace_pattern,
        routing_strategy, route_expression, default_target,
        active, priority, created_at, created_by
    ) VALUES (
        :NEW.rule_name, :NEW.message_type, :NEW.rule_set,
        'CONTENT_BASED', :NEW.condition_expression, :NEW.destination,
        NVL(:NEW.active, 1), NVL(:NEW.priority, 100), 
        NVL(:NEW.created_at, SYSTIMESTAMP), :NEW.created_by
    );
END;
/

COMMENT ON TABLE routing_rules IS 'Compatibility view mapping to routing_configs table';

-- Trigger to handle UPDATEs on the routing_rules view
CREATE OR REPLACE TRIGGER trg_routing_rules_upd
INSTEAD OF UPDATE ON routing_rules
FOR EACH ROW
BEGIN
    UPDATE routing_configs SET
        config_name = :NEW.rule_name,
        message_type_pattern = :NEW.message_type,
        namespace_pattern = :NEW.rule_set,
        route_expression = :NEW.condition_expression,
        default_target = :NEW.destination,
        active = NVL(:NEW.active, active),
        priority = NVL(:NEW.priority, priority),
        updated_at = SYSTIMESTAMP,
        updated_by = :NEW.updated_by
    WHERE config_id = :OLD.rule_id;
END;
/

-- Note: DDL auto-commits, explicit COMMIT not needed

PROMPT =====================================================
PROMPT Compatibility views created successfully.
PROMPT =====================================================
