-- =============================================================================
-- MIGRATION 002: CREATE ALL TABLES
-- Purpose: Enhanced integration management schema with comprehensive EIP support
-- Author: GprintEx Team
-- Date: 2026-01-28
-- =============================================================================

-- =============================================================================
-- PART 1: CORE MESSAGE INFRASTRUCTURE
-- =============================================================================

-- Message envelope - core metadata only
CREATE TABLE messages (
    message_id VARCHAR2(100) PRIMARY KEY,
    message_uuid RAW(16) DEFAULT SYS_GUID() NOT NULL UNIQUE,
    
    -- Classification
    message_type VARCHAR2(50) NOT NULL,
    content_type VARCHAR2(100) DEFAULT 'application/json',
    
    -- Routing
    source_endpoint VARCHAR2(200),
    target_endpoint VARCHAR2(200),
    
    -- Correlation
    correlation_id VARCHAR2(100),
    conversation_id VARCHAR2(100),
    causation_id VARCHAR2(100),
    
    -- Priority and SLA
    priority NUMBER(1) DEFAULT 5 NOT NULL,
    sla_class VARCHAR2(20) DEFAULT 'NORMAL',
    deadline_at TIMESTAMP,
    
    -- Lifecycle
    status VARCHAR2(20) DEFAULT 'CREATED' NOT NULL,
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    received_at TIMESTAMP,
    processing_started_at TIMESTAMP,
    completed_at TIMESTAMP,
    
    -- Tenant isolation
    tenant_id VARCHAR2(50) NOT NULL,
    namespace VARCHAR2(100),
    
    -- Retry control
    retry_count NUMBER DEFAULT 0,
    max_retries NUMBER DEFAULT 3,
    next_retry_at TIMESTAMP,
    backoff_strategy VARCHAR2(20) DEFAULT 'EXPONENTIAL',
    
    -- Metadata
    headers CLOB CHECK (headers IS JSON),
    properties CLOB CHECK (properties IS JSON),
    
    CONSTRAINT chk_msg_status CHECK (status IN (
        'CREATED', 'QUEUED', 'ROUTING', 'TRANSFORMING', 'VALIDATING',
        'PROCESSING', 'AGGREGATING', 'SPLITTING', 'ENRICHING',
        'COMPLETED', 'FAILED', 'DEAD_LETTER', 'ARCHIVED'
    )),
    CONSTRAINT chk_msg_priority CHECK (priority BETWEEN 1 AND 9),
    CONSTRAINT chk_msg_sla CHECK (sla_class IN ('CRITICAL', 'HIGH', 'NORMAL', 'LOW', 'BATCH'))
);

CREATE INDEX idx_messages_correlation ON messages(correlation_id, created_at DESC);
CREATE INDEX idx_messages_conversation ON messages(conversation_id, created_at);
CREATE INDEX idx_messages_tenant_status ON messages(tenant_id, status, priority, created_at);
CREATE INDEX idx_messages_type_status ON messages(message_type, status, created_at);

COMMENT ON TABLE messages IS 'Core message envelope with metadata and lifecycle tracking';
COMMENT ON COLUMN messages.conversation_id IS 'Groups messages in a multi-step business process';
COMMENT ON COLUMN messages.causation_id IS 'ID of the message that triggered this message';

-- Message payloads - separated for better performance
CREATE TABLE message_payloads (
    message_id VARCHAR2(100) PRIMARY KEY,
    payload CLOB NOT NULL,
    payload_hash VARCHAR2(64),
    payload_size NUMBER,
    compressed NUMBER(1) DEFAULT 0,
    encrypted NUMBER(1) DEFAULT 0,
    encryption_key_id VARCHAR2(100),
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    
    CONSTRAINT fk_payload_message FOREIGN KEY (message_id)
        REFERENCES messages(message_id) ON DELETE CASCADE
);

CREATE INDEX idx_payload_hash ON message_payloads(payload_hash);

COMMENT ON TABLE message_payloads IS 'Message payload data separated from envelope for performance';

-- Message state transitions - complete audit trail
CREATE TABLE message_state_transitions (
    transition_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id VARCHAR2(100) NOT NULL,
    from_status VARCHAR2(20),
    to_status VARCHAR2(20) NOT NULL,
    transitioned_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    reason VARCHAR2(500),
    error_code VARCHAR2(50),
    error_message VARCHAR2(4000),
    stack_trace CLOB,
    triggered_by VARCHAR2(100),
    
    CONSTRAINT fk_transition_message FOREIGN KEY (message_id)
        REFERENCES messages(message_id) ON DELETE CASCADE,
    CONSTRAINT chk_transition_from_status CHECK (from_status IS NULL OR from_status IN (
        'CREATED', 'QUEUED', 'ROUTING', 'TRANSFORMING', 'VALIDATING',
        'PROCESSING', 'AGGREGATING', 'SPLITTING', 'ENRICHING',
        'COMPLETED', 'FAILED', 'DEAD_LETTER', 'ARCHIVED'
    )),
    CONSTRAINT chk_transition_to_status CHECK (to_status IN (
        'CREATED', 'QUEUED', 'ROUTING', 'TRANSFORMING', 'VALIDATING',
        'PROCESSING', 'AGGREGATING', 'SPLITTING', 'ENRICHING',
        'COMPLETED', 'FAILED', 'DEAD_LETTER', 'ARCHIVED'
    ))
);

CREATE INDEX idx_transition_message ON message_state_transitions(message_id, transitioned_at);
CREATE INDEX idx_transition_status ON message_state_transitions(to_status, transitioned_at);

COMMENT ON TABLE message_state_transitions IS 'Complete state machine audit trail for messages';

-- =============================================================================
-- PART 2: ROUTING AND TRANSFORMATION
-- =============================================================================

-- Routing configurations
CREATE TABLE routing_configs (
    config_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    config_name VARCHAR2(100) NOT NULL UNIQUE,
    config_version NUMBER DEFAULT 1 NOT NULL,
    
    -- Matching criteria
    message_type_pattern VARCHAR2(200),
    content_type_pattern VARCHAR2(200),
    namespace_pattern VARCHAR2(200),
    
    -- Routing logic
    routing_strategy VARCHAR2(50) NOT NULL,
    route_expression CLOB,
    
    -- Target configuration
    default_target VARCHAR2(200),
    failover_target VARCHAR2(200),
    
    -- Behavior
    transformation_required NUMBER(1) DEFAULT 0,
    transformation_id NUMBER,
    validation_required NUMBER(1) DEFAULT 0,
    enrichment_required NUMBER(1) DEFAULT 0,
    
    -- Lifecycle
    active NUMBER(1) DEFAULT 1,
    priority NUMBER DEFAULT 100,
    effective_from TIMESTAMP DEFAULT SYSTIMESTAMP,
    effective_until TIMESTAMP,
    
    -- Metadata
    description VARCHAR2(500),
    tags CLOB CHECK (tags IS JSON),
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    created_by VARCHAR2(100),
    updated_at TIMESTAMP,
    updated_by VARCHAR2(100),
    
    CONSTRAINT chk_routing_strategy CHECK (routing_strategy IN (
        'DIRECT', 'CONTENT_BASED', 'RECIPIENT_LIST', 'MULTICAST', 
        'SPLITTER', 'AGGREGATOR', 'DYNAMIC'
    ))
);

CREATE INDEX idx_routing_type_pattern ON routing_configs(message_type_pattern, active, priority);
CREATE INDEX idx_routing_active ON routing_configs(active, priority, effective_from);

COMMENT ON TABLE routing_configs IS 'Routing configurations with pattern matching and versioning';

-- Routing decisions - tracks actual routing decisions made
CREATE TABLE routing_decisions (
    decision_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id VARCHAR2(100) NOT NULL,
    config_id NUMBER,
    
    -- Decision details
    matched_pattern VARCHAR2(200),
    selected_target VARCHAR2(200) NOT NULL,
    routing_strategy VARCHAR2(50),
    
    -- Metrics
    evaluation_time_ms NUMBER,
    decided_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    
    -- Alternatives considered
    alternatives_evaluated CLOB CHECK (alternatives_evaluated IS JSON),
    
    CONSTRAINT fk_routing_message FOREIGN KEY (message_id)
        REFERENCES messages(message_id) ON DELETE CASCADE,
    CONSTRAINT fk_routing_config FOREIGN KEY (config_id)
        REFERENCES routing_configs(config_id)
);

CREATE INDEX idx_routing_decision_msg ON routing_decisions(message_id);
CREATE INDEX idx_routing_decision_config ON routing_decisions(config_id, decided_at);
CREATE INDEX idx_routing_decision_target ON routing_decisions(selected_target, decided_at);

COMMENT ON TABLE routing_decisions IS 'Audit trail of routing decisions for analytics and debugging';

-- Transformation templates
CREATE TABLE transformation_templates (
    template_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    template_name VARCHAR2(100) NOT NULL UNIQUE,
    template_version NUMBER DEFAULT 1 NOT NULL,
    
    -- Template definition
    source_format VARCHAR2(50) NOT NULL,
    target_format VARCHAR2(50) NOT NULL,
    transformation_type VARCHAR2(50) NOT NULL,
    transformation_script CLOB NOT NULL,
    script_language VARCHAR2(20) DEFAULT 'XSLT',
    
    -- Field mappings (for simple transformations)
    field_mappings CLOB CHECK (field_mappings IS JSON),
    
    -- Validation
    input_schema CLOB,
    output_schema CLOB,
    
    -- Performance hints
    cacheable NUMBER(1) DEFAULT 0,
    stateless NUMBER(1) DEFAULT 1,
    
    -- Lifecycle
    active NUMBER(1) DEFAULT 1,
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    created_by VARCHAR2(100),
    updated_at TIMESTAMP,
    updated_by VARCHAR2(100),
    
    -- Testing
    test_input CLOB,
    expected_output CLOB,
    
    CONSTRAINT chk_transform_type CHECK (transformation_type IN (
        'XSLT', 'JSONPATH', 'SCRIPT', 'TEMPLATE', 'MAPPING', 'ENRICHMENT'
    ))
);

CREATE INDEX idx_transform_format ON transformation_templates(source_format, target_format, active);

COMMENT ON TABLE transformation_templates IS 'Reusable transformation templates with versioning';

-- Add FK constraint for routing_configs.transformation_id now that transformation_templates exists
ALTER TABLE routing_configs 
    ADD CONSTRAINT fk_routing_transformation 
    FOREIGN KEY (transformation_id) REFERENCES transformation_templates(template_id);

-- Transformation executions - tracks actual transformations
CREATE TABLE transformation_executions (
    execution_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id VARCHAR2(100) NOT NULL,
    template_id NUMBER,
    
    -- Execution details
    input_format VARCHAR2(50),
    output_format VARCHAR2(50),
    transformation_type VARCHAR2(50),
    
    -- Performance metrics
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    duration_ms NUMBER,
    input_size_bytes NUMBER,
    output_size_bytes NUMBER,
    
    -- Result
    status VARCHAR2(20) NOT NULL,
    error_message VARCHAR2(4000),
    
    -- Caching
    cache_hit NUMBER(1) DEFAULT 0,
    cache_key VARCHAR2(200),
    
    CONSTRAINT fk_transform_message FOREIGN KEY (message_id)
        REFERENCES messages(message_id) ON DELETE CASCADE,
    CONSTRAINT fk_transform_template FOREIGN KEY (template_id)
        REFERENCES transformation_templates(template_id),
    CONSTRAINT chk_transform_exec_status CHECK (status IN ('SUCCESS', 'FAILED', 'PARTIAL'))
);

CREATE INDEX idx_transform_exec_msg ON transformation_executions(message_id);
CREATE INDEX idx_transform_exec_template ON transformation_executions(template_id, started_at);
CREATE INDEX idx_transform_exec_perf ON transformation_executions(template_id, duration_ms);

COMMENT ON TABLE transformation_executions IS 'Audit trail of transformation executions with performance metrics';

-- =============================================================================
-- PART 3: ORCHESTRATION AND WORKFLOWS
-- =============================================================================

-- Process definitions - reusable workflow templates
CREATE TABLE process_definitions (
    process_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    process_key VARCHAR2(100) NOT NULL UNIQUE,
    process_version NUMBER DEFAULT 1 NOT NULL,
    process_name VARCHAR2(200) NOT NULL,
    
    -- Definition
    process_type VARCHAR2(50) NOT NULL,
    process_definition CLOB NOT NULL CHECK (process_definition IS JSON),
    
    -- Behavior
    max_duration_seconds NUMBER,
    timeout_action VARCHAR2(50) DEFAULT 'FAIL',
    compensation_supported NUMBER(1) DEFAULT 0,
    
    -- State
    active NUMBER(1) DEFAULT 1,
    deployed_at TIMESTAMP,
    deprecated_at TIMESTAMP,
    
    -- Metadata
    description VARCHAR2(1000),
    tags CLOB CHECK (tags IS JSON),
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    created_by VARCHAR2(100),
    
    CONSTRAINT chk_process_type CHECK (process_type IN (
        'SEQUENTIAL', 'PARALLEL', 'CONDITIONAL', 'SAGA', 'CHOREOGRAPHY', 'ORCHESTRATION'
    ))
);

CREATE INDEX idx_process_key_version ON process_definitions(process_key, process_version DESC);
CREATE INDEX idx_process_active ON process_definitions(active, deployed_at);

COMMENT ON TABLE process_definitions IS 'Reusable process/workflow definitions with versioning';

-- Process instances - active workflow executions
CREATE TABLE process_instances (
    instance_id VARCHAR2(100) PRIMARY KEY,
    process_id NUMBER NOT NULL,
    
    -- Context
    tenant_id VARCHAR2(50) NOT NULL,
    conversation_id VARCHAR2(100) NOT NULL,
    parent_instance_id VARCHAR2(100),
    
    -- State
    status VARCHAR2(20) DEFAULT 'CREATED' NOT NULL,
    current_step VARCHAR2(100),
    
    -- Timing
    started_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    deadline_at TIMESTAMP,
    
    -- Variables and context
    process_variables CLOB CHECK (process_variables IS JSON),
    execution_context CLOB CHECK (execution_context IS JSON),
    
    -- Result
    outcome VARCHAR2(20),
    error_message VARCHAR2(4000),
    
    CONSTRAINT fk_instance_process FOREIGN KEY (process_id)
        REFERENCES process_definitions(process_id),
    CONSTRAINT fk_instance_parent FOREIGN KEY (parent_instance_id)
        REFERENCES process_instances(instance_id) ON DELETE SET NULL,
    CONSTRAINT chk_instance_status CHECK (status IN (
        'CREATED', 'RUNNING', 'WAITING', 'SUSPENDED', 
        'COMPLETED', 'FAILED', 'COMPENSATING', 'COMPENSATED', 'TERMINATED'
    )),
    CONSTRAINT chk_instance_outcome CHECK (outcome IN (
        'SUCCESS', 'BUSINESS_ERROR', 'TECHNICAL_ERROR', 'TIMEOUT', 'CANCELLED', 'COMPENSATED'
    ))
);

CREATE INDEX idx_instance_process ON process_instances(process_id, started_at);
CREATE INDEX idx_instance_conversation ON process_instances(conversation_id);
CREATE INDEX idx_instance_status ON process_instances(status, deadline_at);
CREATE INDEX idx_instance_tenant ON process_instances(tenant_id, status, started_at);

COMMENT ON TABLE process_instances IS 'Active process instances with state and context';

-- Process steps - granular step execution tracking
CREATE TABLE process_steps (
    step_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    instance_id VARCHAR2(100) NOT NULL,
    
    -- Step identification
    step_key VARCHAR2(100) NOT NULL,
    step_name VARCHAR2(200),
    step_type VARCHAR2(50) NOT NULL,
    step_sequence NUMBER NOT NULL,
    
    -- Execution
    status VARCHAR2(20) DEFAULT 'PENDING' NOT NULL,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    retry_count NUMBER DEFAULT 0,
    
    -- Associated message
    message_id VARCHAR2(100),
    
    -- Result
    output_data CLOB,
    error_message VARCHAR2(4000),
    
    -- Compensation
    compensation_required NUMBER(1) DEFAULT 0,
    compensation_step_id NUMBER,
    
    CONSTRAINT fk_step_instance FOREIGN KEY (instance_id)
        REFERENCES process_instances(instance_id) ON DELETE CASCADE,
    CONSTRAINT fk_step_message FOREIGN KEY (message_id)
        REFERENCES messages(message_id),
    CONSTRAINT fk_step_compensation FOREIGN KEY (compensation_step_id)
        REFERENCES process_steps(step_id) ON DELETE SET NULL,
    CONSTRAINT chk_step_status CHECK (status IN (
        'PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'SKIPPED', 'COMPENSATED'
    )),
    CONSTRAINT chk_step_type CHECK (step_type IN (
        'SERVICE_TASK', 'SEND_TASK', 'RECEIVE_TASK', 'USER_TASK',
        'SCRIPT_TASK', 'BUSINESS_RULE', 'GATEWAY', 'SUBPROCESS', 'COMPENSATION'
    ))
);

CREATE INDEX idx_step_instance ON process_steps(instance_id, step_sequence);
CREATE INDEX idx_step_message ON process_steps(message_id);
CREATE INDEX idx_step_status ON process_steps(status, started_at);

COMMENT ON TABLE process_steps IS 'Granular step-level execution tracking within processes';

-- =============================================================================
-- PART 4: AGGREGATION AND CORRELATION
-- =============================================================================

-- Aggregation definitions
CREATE TABLE aggregation_definitions (
    aggregation_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregation_key VARCHAR2(100) NOT NULL UNIQUE,
    aggregation_name VARCHAR2(200) NOT NULL,
    
    -- Strategy
    aggregation_strategy VARCHAR2(50) NOT NULL,
    correlation_expression CLOB NOT NULL,
    
    -- Completion criteria
    completion_strategy VARCHAR2(50) NOT NULL,
    expected_message_count NUMBER,
    completion_condition CLOB,
    timeout_seconds NUMBER DEFAULT 300,
    
    -- Behavior
    ordered NUMBER(1) DEFAULT 0,
    preserve_order NUMBER(1) DEFAULT 0,
    discard_duplicates NUMBER(1) DEFAULT 1,
    
    -- Output
    output_message_type VARCHAR2(50),
    aggregation_script CLOB,
    
    -- Lifecycle
    active NUMBER(1) DEFAULT 1,
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    created_by VARCHAR2(100),
    
    CONSTRAINT chk_agg_strategy CHECK (aggregation_strategy IN (
        'COLLECT_ALL', 'BATCH', 'TIME_WINDOW', 'SLIDING_WINDOW', 'CUSTOM'
    )),
    CONSTRAINT chk_agg_completion CHECK (completion_strategy IN (
        'SIZE', 'TIMEOUT', 'CONDITION', 'EXTERNAL_TRIGGER'
    ))
);

CREATE INDEX idx_agg_key ON aggregation_definitions(aggregation_key, active);

COMMENT ON TABLE aggregation_definitions IS 'Aggregation pattern definitions and strategies';

-- Aggregation instances - active aggregations
CREATE TABLE aggregation_instances (
    instance_id VARCHAR2(100) PRIMARY KEY,
    aggregation_id NUMBER NOT NULL,
    correlation_id VARCHAR2(100) NOT NULL,
    
    -- State
    status VARCHAR2(20) DEFAULT 'COLLECTING' NOT NULL,
    expected_count NUMBER,
    current_count NUMBER DEFAULT 0,
    
    -- Timing
    started_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    last_message_at TIMESTAMP,
    timeout_at TIMESTAMP,
    completed_at TIMESTAMP,
    
    -- Partial results
    aggregated_payload CLOB,
    aggregation_metadata CLOB CHECK (aggregation_metadata IS JSON),
    
    -- Result
    output_message_id VARCHAR2(100),
    
    CONSTRAINT fk_agg_inst_def FOREIGN KEY (aggregation_id)
        REFERENCES aggregation_definitions(aggregation_id),
    CONSTRAINT fk_agg_inst_output FOREIGN KEY (output_message_id)
        REFERENCES messages(message_id),
    CONSTRAINT chk_agg_inst_status CHECK (status IN (
        'COLLECTING', 'COMPLETE', 'TIMEOUT', 'CANCELLED', 'FAILED'
    ))
);

CREATE INDEX idx_agg_inst_correlation ON aggregation_instances(correlation_id);
CREATE INDEX idx_agg_inst_status ON aggregation_instances(status, timeout_at);
CREATE INDEX idx_agg_inst_def ON aggregation_instances(aggregation_id, started_at);

COMMENT ON TABLE aggregation_instances IS 'Active aggregation instances collecting messages';

-- Aggregation members - messages in an aggregation
CREATE TABLE aggregation_members (
    member_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    instance_id VARCHAR2(100) NOT NULL,
    message_id VARCHAR2(100) NOT NULL,
    
    -- Ordering
    sequence_number NUMBER,
    received_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    
    -- Processing
    included_in_result NUMBER(1) DEFAULT 1,
    exclusion_reason VARCHAR2(200),
    
    CONSTRAINT fk_agg_member_inst FOREIGN KEY (instance_id)
        REFERENCES aggregation_instances(instance_id) ON DELETE CASCADE,
    CONSTRAINT fk_agg_member_msg FOREIGN KEY (message_id)
        REFERENCES messages(message_id),
    CONSTRAINT uk_agg_member UNIQUE (instance_id, message_id)
);

CREATE INDEX idx_agg_member_inst ON aggregation_members(instance_id, sequence_number);
CREATE INDEX idx_agg_member_msg ON aggregation_members(message_id);

COMMENT ON TABLE aggregation_members IS 'Messages participating in an aggregation';

-- =============================================================================
-- PART 5: MONITORING AND METRICS
-- =============================================================================

-- Endpoint metrics - performance tracking per endpoint
CREATE TABLE endpoint_metrics (
    metric_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    endpoint_name VARCHAR2(200) NOT NULL,
    metric_timestamp TIMESTAMP NOT NULL,
    interval_minutes NUMBER DEFAULT 5,
    
    -- Volume metrics
    message_count NUMBER DEFAULT 0,
    byte_count NUMBER DEFAULT 0,
    
    -- Latency metrics (milliseconds)
    avg_latency_ms NUMBER,
    min_latency_ms NUMBER,
    max_latency_ms NUMBER,
    p50_latency_ms NUMBER,
    p95_latency_ms NUMBER,
    p99_latency_ms NUMBER,
    
    -- Success rates
    success_count NUMBER DEFAULT 0,
    failure_count NUMBER DEFAULT 0,
    timeout_count NUMBER DEFAULT 0,
    
    -- Resource utilization
    avg_cpu_percent NUMBER,
    avg_memory_mb NUMBER,
    thread_count NUMBER,
    
    CONSTRAINT uk_endpoint_metric UNIQUE (endpoint_name, metric_timestamp)
);

CREATE INDEX idx_endpoint_metric_time ON endpoint_metrics(endpoint_name, metric_timestamp DESC);

COMMENT ON TABLE endpoint_metrics IS 'Time-series metrics for endpoint performance monitoring';

-- Message processing metrics
CREATE TABLE message_processing_metrics (
    metric_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id VARCHAR2(100) NOT NULL,
    
    -- Processing stages with timestamps
    received_at TIMESTAMP,
    validated_at TIMESTAMP,
    routed_at TIMESTAMP,
    transformed_at TIMESTAMP,
    enriched_at TIMESTAMP,
    processed_at TIMESTAMP,
    completed_at TIMESTAMP,
    
    -- Stage durations (milliseconds)
    validation_duration_ms NUMBER,
    routing_duration_ms NUMBER,
    transformation_duration_ms NUMBER,
    enrichment_duration_ms NUMBER,
    processing_duration_ms NUMBER,
    total_duration_ms NUMBER,
    
    -- Resource metrics
    peak_memory_mb NUMBER,
    cpu_time_ms NUMBER,
    
    -- Hops and transformations
    hop_count NUMBER DEFAULT 1,
    transformation_count NUMBER DEFAULT 0,
    
    CONSTRAINT fk_proc_metric_msg FOREIGN KEY (message_id)
        REFERENCES messages(message_id) ON DELETE CASCADE
);

CREATE INDEX idx_proc_metric_msg ON message_processing_metrics(message_id);
CREATE INDEX idx_proc_metric_duration ON message_processing_metrics(total_duration_ms DESC, completed_at DESC);

COMMENT ON TABLE message_processing_metrics IS 'Detailed processing metrics per message for performance analysis';

-- System health metrics
CREATE TABLE system_health_metrics (
    metric_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    metric_timestamp TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    component_name VARCHAR2(100) NOT NULL,
    
    -- Health status
    status VARCHAR2(20) NOT NULL,
    
    -- Queue depths
    pending_message_count NUMBER DEFAULT 0,
    dead_letter_count NUMBER DEFAULT 0,
    retry_queue_count NUMBER DEFAULT 0,
    
    -- Resource utilization
    cpu_percent NUMBER,
    memory_used_mb NUMBER,
    memory_total_mb NUMBER,
    disk_used_gb NUMBER,
    disk_total_gb NUMBER,
    
    -- Connection pools
    db_connections_active NUMBER,
    db_connections_idle NUMBER,
    http_connections_active NUMBER,
    
    -- Errors and warnings
    error_count_5min NUMBER DEFAULT 0,
    warning_count_5min NUMBER DEFAULT 0,
    
    CONSTRAINT chk_health_status CHECK (status IN ('HEALTHY', 'DEGRADED', 'CRITICAL', 'DOWN'))
);

CREATE INDEX idx_health_component_time ON system_health_metrics(component_name, metric_timestamp DESC);
CREATE INDEX idx_health_status ON system_health_metrics(status, metric_timestamp DESC);

COMMENT ON TABLE system_health_metrics IS 'System-wide health and resource utilization metrics';

-- Alert definitions
CREATE TABLE alert_definitions (
    alert_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    alert_name VARCHAR2(100) NOT NULL UNIQUE,
    
    -- Condition
    metric_name VARCHAR2(100) NOT NULL,
    condition_operator VARCHAR2(10) NOT NULL,
    threshold_value NUMBER NOT NULL,
    duration_minutes NUMBER DEFAULT 5,
    
    -- Severity and notification
    severity VARCHAR2(20) NOT NULL,
    notification_channels CLOB CHECK (notification_channels IS JSON),
    
    -- Behavior
    active NUMBER(1) DEFAULT 1,
    cooldown_minutes NUMBER DEFAULT 15,
    
    -- Description
    description VARCHAR2(500),
    remediation_steps CLOB,
    
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    created_by VARCHAR2(100),
    
    CONSTRAINT chk_alert_operator CHECK (condition_operator IN ('>', '<', '>=', '<=', '=', '!=')),
    CONSTRAINT chk_alert_severity CHECK (severity IN ('INFO', 'WARNING', 'ERROR', 'CRITICAL'))
);

CREATE INDEX idx_alert_metric ON alert_definitions(metric_name, active);

COMMENT ON TABLE alert_definitions IS 'Alert rules for proactive monitoring and notifications';

-- Alert instances
CREATE TABLE alert_instances (
    instance_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    alert_id NUMBER NOT NULL,
    
    -- Trigger details
    triggered_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    trigger_value NUMBER,
    
    -- State
    status VARCHAR2(20) DEFAULT 'OPEN' NOT NULL,
    acknowledged_at TIMESTAMP,
    acknowledged_by VARCHAR2(100),
    resolved_at TIMESTAMP,
    resolved_by VARCHAR2(100),
    resolution_notes VARCHAR2(1000),
    
    -- Notifications
    notifications_sent CLOB CHECK (notifications_sent IS JSON),
    
    CONSTRAINT fk_alert_inst_def FOREIGN KEY (alert_id)
        REFERENCES alert_definitions(alert_id),
    CONSTRAINT chk_alert_inst_status CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'SUPPRESSED'))
);

CREATE INDEX idx_alert_inst_alert ON alert_instances(alert_id, triggered_at DESC);
CREATE INDEX idx_alert_inst_status ON alert_instances(status, triggered_at DESC);

COMMENT ON TABLE alert_instances IS 'Active and historical alert occurrences';

-- =============================================================================
-- PART 6: IDEMPOTENCY AND DEDUPLICATION
-- =============================================================================

-- Enhanced deduplication with content-based and ID-based strategies
CREATE TABLE message_deduplication (
    dedup_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    
    -- Deduplication keys
    message_id VARCHAR2(100),
    content_hash VARCHAR2(64) NOT NULL,
    business_key VARCHAR2(200),
    
    -- Context
    tenant_id VARCHAR2(50) NOT NULL,
    message_type VARCHAR2(50),
    
    -- Tracking
    first_seen_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    last_seen_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    occurrence_count NUMBER DEFAULT 1,
    
    -- Associated messages
    original_message_id VARCHAR2(100) NOT NULL,
    duplicate_message_ids CLOB CHECK (duplicate_message_ids IS JSON),
    
    -- Expiry
    expires_at TIMESTAMP NOT NULL,
    
    CONSTRAINT uk_dedup_content UNIQUE (tenant_id, content_hash, message_type)
    -- Note: business_key uniqueness handled by function-based index below
    -- since business_key is nullable and Oracle UNIQUE constraints allow multiple NULLs
);

-- Function-based unique index for business_key deduplication (handles NULLs)
CREATE UNIQUE INDEX uk_dedup_business_key ON message_deduplication(
    tenant_id, NVL(business_key, '<<NULL>>'), message_type
);

CREATE INDEX idx_dedup_expires ON message_deduplication(expires_at);
CREATE INDEX idx_dedup_tenant_type ON message_deduplication(tenant_id, message_type, first_seen_at);
CREATE INDEX idx_dedup_message_id ON message_deduplication(message_id);

COMMENT ON TABLE message_deduplication IS 'Content and business-key based message deduplication';

-- Idempotency tokens for API operations
CREATE TABLE idempotency_tokens (
    token_id VARCHAR2(100) PRIMARY KEY,
    
    -- Context
    operation_name VARCHAR2(100) NOT NULL,
    tenant_id VARCHAR2(50) NOT NULL,
    user_id VARCHAR2(100),
    
    -- Request details
    request_hash VARCHAR2(64) NOT NULL,
    request_payload CLOB,
    
    -- Response caching
    response_status NUMBER,
    response_payload CLOB,
    response_headers CLOB CHECK (response_headers IS JSON),
    
    -- Lifecycle
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    
    -- State
    status VARCHAR2(20) DEFAULT 'PROCESSING' NOT NULL,
    
    CONSTRAINT chk_idem_status CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED', 'EXPIRED'))
);

CREATE INDEX idx_idem_expires ON idempotency_tokens(expires_at);
CREATE INDEX idx_idem_tenant_op ON idempotency_tokens(tenant_id, operation_name, created_at);
CREATE INDEX idx_idem_hash ON idempotency_tokens(request_hash, operation_name);

COMMENT ON TABLE idempotency_tokens IS 'Idempotency tokens for exactly-once API operation semantics';

-- =============================================================================
-- PART 7: DATA LINEAGE AND TRACING
-- =============================================================================

-- Message lineage - tracks message genealogy
CREATE TABLE message_lineage (
    lineage_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    
    -- Source and derived messages
    source_message_id VARCHAR2(100) NOT NULL,
    derived_message_id VARCHAR2(100) NOT NULL,
    
    -- Relationship type
    relationship_type VARCHAR2(50) NOT NULL,
    
    -- Transformation details
    transformation_id NUMBER,
    split_index NUMBER,
    merge_group VARCHAR2(100),
    
    -- Context
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    created_by_component VARCHAR2(100),
    
    CONSTRAINT fk_lineage_source FOREIGN KEY (source_message_id)
        REFERENCES messages(message_id),
    CONSTRAINT fk_lineage_derived FOREIGN KEY (derived_message_id)
        REFERENCES messages(message_id),
    CONSTRAINT fk_lineage_transform FOREIGN KEY (transformation_id)
        REFERENCES transformation_templates(template_id),
    CONSTRAINT chk_lineage_type CHECK (relationship_type IN (
        'TRANSFORMED', 'SPLIT', 'AGGREGATED', 'ENRICHED', 'FILTERED', 'ROUTED', 'COPIED'
    )),
    CONSTRAINT uk_lineage UNIQUE (source_message_id, derived_message_id, relationship_type)
);

CREATE INDEX idx_lineage_source ON message_lineage(source_message_id, created_at);
CREATE INDEX idx_lineage_derived ON message_lineage(derived_message_id);

COMMENT ON TABLE message_lineage IS 'Message genealogy and transformation lineage tracking';

-- Distributed tracing spans
CREATE TABLE trace_spans (
    span_id VARCHAR2(100) PRIMARY KEY,
    trace_id VARCHAR2(100) NOT NULL,
    parent_span_id VARCHAR2(100),
    
    -- Span details
    span_name VARCHAR2(200) NOT NULL,
    span_kind VARCHAR2(20) NOT NULL,
    
    -- Timing
    start_timestamp TIMESTAMP NOT NULL,
    end_timestamp TIMESTAMP,
    duration_micros NUMBER,
    
    -- Context
    service_name VARCHAR2(100),
    operation_name VARCHAR2(100),
    
    -- Correlation
    message_id VARCHAR2(100),
    correlation_id VARCHAR2(100),
    conversation_id VARCHAR2(100),
    
    -- Status
    status_code VARCHAR2(20),
    status_message VARCHAR2(500),
    
    -- Attributes and tags
    attributes CLOB CHECK (attributes IS JSON),
    
    CONSTRAINT fk_span_parent FOREIGN KEY (parent_span_id)
        REFERENCES trace_spans(span_id) ON DELETE SET NULL,
    CONSTRAINT fk_span_message FOREIGN KEY (message_id)
        REFERENCES messages(message_id) ON DELETE CASCADE,
    CONSTRAINT chk_span_kind CHECK (span_kind IN ('CLIENT', 'SERVER', 'PRODUCER', 'CONSUMER', 'INTERNAL'))
);

CREATE INDEX idx_span_trace ON trace_spans(trace_id, start_timestamp);
CREATE INDEX idx_span_message ON trace_spans(message_id);
CREATE INDEX idx_span_parent ON trace_spans(parent_span_id);
CREATE INDEX idx_span_correlation ON trace_spans(correlation_id, start_timestamp);

COMMENT ON TABLE trace_spans IS 'Distributed tracing spans for end-to-end observability';

-- =============================================================================
-- PART 8: CONFIGURATION AND GOVERNANCE
-- =============================================================================

-- Integration endpoints registry
CREATE TABLE integration_endpoints (
    endpoint_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    endpoint_name VARCHAR2(200) NOT NULL UNIQUE,
    endpoint_type VARCHAR2(50) NOT NULL,
    
    -- Connection details
    protocol VARCHAR2(20) NOT NULL,
    hostname VARCHAR2(200),
    port NUMBER,
    base_path VARCHAR2(500),
    connection_url VARCHAR2(1000),
    
    -- Authentication
    auth_type VARCHAR2(50),
    credential_reference VARCHAR2(100),
    
    -- Behavior
    retry_policy_id NUMBER,
    timeout_seconds NUMBER DEFAULT 30,
    max_concurrent_connections NUMBER DEFAULT 10,
    
    -- Rate limiting
    rate_limit_enabled NUMBER(1) DEFAULT 0,
    rate_limit_per_second NUMBER,
    rate_limit_burst NUMBER,
    
    -- Circuit breaker
    circuit_breaker_enabled NUMBER(1) DEFAULT 0,
    circuit_breaker_threshold NUMBER DEFAULT 5,
    circuit_breaker_timeout_seconds NUMBER DEFAULT 60,
    
    -- Health check
    health_check_url VARCHAR2(500),
    health_check_interval_seconds NUMBER DEFAULT 60,
    
    -- Lifecycle
    status VARCHAR2(20) DEFAULT 'ACTIVE' NOT NULL,
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    created_by VARCHAR2(100),
    updated_at TIMESTAMP,
    updated_by VARCHAR2(100),
    
    -- Metadata
    description VARCHAR2(1000),
    documentation_url VARCHAR2(500),
    tags CLOB CHECK (tags IS JSON),
    
    CONSTRAINT chk_endpoint_type CHECK (endpoint_type IN (
        'REST', 'SOAP', 'GRAPHQL', 'MESSAGING', 'DATABASE', 'FILE', 'FTP', 'SFTP', 'EMAIL', 'CUSTOM'
    )),
    CONSTRAINT chk_endpoint_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'DEPRECATED', 'TESTING'))
);

CREATE INDEX idx_endpoint_type ON integration_endpoints(endpoint_type, status);
CREATE INDEX idx_endpoint_status ON integration_endpoints(status);

COMMENT ON TABLE integration_endpoints IS 'Registry of all integration endpoints with configuration';

-- Retry policies
CREATE TABLE retry_policies (
    policy_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    policy_name VARCHAR2(100) NOT NULL UNIQUE,
    
    -- Strategy
    retry_strategy VARCHAR2(50) NOT NULL,
    max_attempts NUMBER DEFAULT 3,
    
    -- Backoff configuration
    initial_delay_ms NUMBER DEFAULT 1000,
    max_delay_ms NUMBER DEFAULT 60000,
    backoff_multiplier NUMBER DEFAULT 2,
    jitter_enabled NUMBER(1) DEFAULT 1,
    
    -- Conditions
    retry_on_status_codes VARCHAR2(200),
    retry_on_exceptions VARCHAR2(500),
    
    -- Lifecycle
    active NUMBER(1) DEFAULT 1,
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    created_by VARCHAR2(100),
    
    CONSTRAINT chk_retry_strategy CHECK (retry_strategy IN (
        'FIXED', 'EXPONENTIAL', 'LINEAR', 'FIBONACCI', 'CUSTOM'
    ))
);

CREATE INDEX idx_retry_policy_active ON retry_policies(active);

COMMENT ON TABLE retry_policies IS 'Reusable retry policy configurations';

-- Add FK constraint for integration_endpoints.retry_policy_id now that retry_policies exists
ALTER TABLE integration_endpoints 
    ADD CONSTRAINT fk_endpoint_retry_policy 
    FOREIGN KEY (retry_policy_id) REFERENCES retry_policies(policy_id);

-- Data quality rules
CREATE TABLE data_quality_rules (
    rule_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_name VARCHAR2(100) NOT NULL UNIQUE,
    rule_type VARCHAR2(50) NOT NULL,
    
    -- Scope
    message_type_pattern VARCHAR2(200),
    field_path VARCHAR2(500),
    
    -- Validation logic
    validation_expression CLOB NOT NULL,
    validation_message VARCHAR2(500),
    
    -- Severity
    severity VARCHAR2(20) NOT NULL,
    blocking NUMBER(1) DEFAULT 0,
    
    -- Remediation
    auto_remediate NUMBER(1) DEFAULT 0,
    remediation_script CLOB,
    
    -- Lifecycle
    active NUMBER(1) DEFAULT 1,
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    created_by VARCHAR2(100),
    
    CONSTRAINT chk_dq_rule_type CHECK (rule_type IN (
        'REQUIRED', 'FORMAT', 'RANGE', 'ENUM', 'REGEX', 'CUSTOM', 'REFERENTIAL', 'CONSISTENCY'
    )),
    CONSTRAINT chk_dqr_severity CHECK (severity IN ('INFO', 'WARNING', 'ERROR', 'CRITICAL'))
);

CREATE INDEX idx_dq_rule_pattern ON data_quality_rules(message_type_pattern, active);

COMMENT ON TABLE data_quality_rules IS 'Data quality validation rules for messages';

-- Data quality violations
CREATE TABLE data_quality_violations (
    violation_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id VARCHAR2(100) NOT NULL,
    rule_id NUMBER NOT NULL,
    
    -- Violation details
    field_path VARCHAR2(500),
    actual_value VARCHAR2(4000),
    expected_value VARCHAR2(4000),
    
    -- Context
    detected_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    severity VARCHAR2(20) NOT NULL,
    
    -- Resolution
    status VARCHAR2(20) DEFAULT 'OPEN' NOT NULL,
    remediated NUMBER(1) DEFAULT 0,
    remediation_action VARCHAR2(200),
    resolved_at TIMESTAMP,
    resolved_by VARCHAR2(100),
    
    CONSTRAINT fk_dq_violation_msg FOREIGN KEY (message_id)
        REFERENCES messages(message_id) ON DELETE CASCADE,
    CONSTRAINT fk_dq_violation_rule FOREIGN KEY (rule_id)
        REFERENCES data_quality_rules(rule_id),
    CONSTRAINT chk_dq_viol_status CHECK (status IN ('OPEN', 'REMEDIATED', 'ACCEPTED', 'IGNORED'))
);

CREATE INDEX idx_dq_violation_msg ON data_quality_violations(message_id);
CREATE INDEX idx_dq_violation_rule ON data_quality_violations(rule_id, detected_at);
CREATE INDEX idx_dq_violation_status ON data_quality_violations(status, severity, detected_at);

COMMENT ON TABLE data_quality_violations IS 'Data quality violations detected in messages';

-- =============================================================================
-- PART 9: ARCHIVAL AND RETENTION
-- =============================================================================

-- Retention policies
CREATE TABLE retention_policies (
    policy_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    policy_name VARCHAR2(100) NOT NULL UNIQUE,
    
    -- Scope
    applies_to_table VARCHAR2(100) NOT NULL,
    message_type_pattern VARCHAR2(200),
    status_pattern VARCHAR2(200),
    
    -- Retention rules
    retention_days NUMBER NOT NULL,
    archive_before_delete NUMBER(1) DEFAULT 1,
    archive_location VARCHAR2(500),
    
    -- Execution
    execution_schedule VARCHAR2(100),
    last_executed_at TIMESTAMP,
    next_execution_at TIMESTAMP,
    
    -- Lifecycle
    active NUMBER(1) DEFAULT 1,
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    created_by VARCHAR2(100),
    
    CONSTRAINT chk_retention_table CHECK (applies_to_table IN (
        'messages', 'message_payloads', 'endpoint_metrics', 'trace_spans',
        'process_instances', 'aggregation_instances', 'message_deduplication'
    )),
    CONSTRAINT chk_retention_days CHECK (retention_days BETWEEN 1 AND 3650)
);

CREATE INDEX idx_retention_table ON retention_policies(applies_to_table, active);

COMMENT ON TABLE retention_policies IS 'Data retention and archival policies';

-- Archive metadata
CREATE TABLE archive_metadata (
    archive_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    archive_name VARCHAR2(200) NOT NULL,
    
    -- Content
    source_table VARCHAR2(100) NOT NULL,
    record_count NUMBER NOT NULL,
    
    -- Date range
    data_from_date TIMESTAMP NOT NULL,
    data_to_date TIMESTAMP NOT NULL,
    
    -- Storage
    archive_format VARCHAR2(20) NOT NULL,
    archive_location VARCHAR2(1000) NOT NULL,
    compressed NUMBER(1) DEFAULT 1,
    encrypted NUMBER(1) DEFAULT 1,
    
    -- Metadata
    size_bytes NUMBER,
    checksum VARCHAR2(64),
    
    -- Lifecycle
    archived_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    archived_by VARCHAR2(100),
    
    -- Retrieval
    accessible NUMBER(1) DEFAULT 1,
    retrieval_cost_class VARCHAR2(20),
    
    CONSTRAINT chk_archive_format CHECK (archive_format IN ('PARQUET', 'AVRO', 'JSON', 'CSV', 'CUSTOM'))
);

CREATE INDEX idx_archive_table_date ON archive_metadata(source_table, data_to_date);
CREATE INDEX idx_archive_location ON archive_metadata(archive_location);

COMMENT ON TABLE archive_metadata IS 'Metadata for archived data for compliance and retrieval';

-- =============================================================================
-- PART 10: ETL AND STAGING (for backward compatibility)
-- =============================================================================

-- ETL session tracking
CREATE TABLE etl_sessions (
    session_id VARCHAR2(100) PRIMARY KEY,
    source_system VARCHAR2(100) NOT NULL,
    entity_type VARCHAR2(50) NOT NULL,
    status VARCHAR2(20) DEFAULT 'ACTIVE' NOT NULL,
    record_count NUMBER DEFAULT 0,
    success_count NUMBER DEFAULT 0,
    error_count NUMBER DEFAULT 0,
    started_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    started_by VARCHAR2(100),
    metadata CLOB,
    CONSTRAINT chk_etl_session_status CHECK (status IN ('ACTIVE', 'PROCESSING', 'COMPLETED', 'FAILED', 'ROLLED_BACK'))
);

CREATE INDEX idx_etl_sessions_status ON etl_sessions(status);
CREATE INDEX idx_etl_sessions_source ON etl_sessions(source_system, entity_type);
CREATE INDEX idx_etl_sessions_started ON etl_sessions(started_at);

COMMENT ON TABLE etl_sessions IS 'Tracks ETL session lifecycle and metadata';

-- Generic staging table for incoming data
CREATE TABLE etl_staging (
    session_id VARCHAR2(100) NOT NULL,
    seq_num NUMBER NOT NULL,
    entity_type VARCHAR2(50) NOT NULL,
    source_system VARCHAR2(100),
    raw_data CLOB,
    transformed_data CLOB,
    status VARCHAR2(20) DEFAULT 'PENDING' NOT NULL,
    error_message VARCHAR2(4000),
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    processed_at TIMESTAMP,
    CONSTRAINT pk_etl_staging PRIMARY KEY (session_id, seq_num),
    CONSTRAINT fk_etl_staging_session FOREIGN KEY (session_id) 
        REFERENCES etl_sessions(session_id) ON DELETE CASCADE,
    CONSTRAINT chk_etl_staging_status CHECK (status IN ('PENDING', 'TRANSFORMED', 'VALIDATED', 'LOADED', 'FAILED', 'SKIPPED'))
);

CREATE INDEX idx_etl_staging_status ON etl_staging(status);
CREATE INDEX idx_etl_staging_entity ON etl_staging(entity_type);

CREATE SEQUENCE etl_staging_seq START WITH 1 INCREMENT BY 1 CACHE 100;

-- Trigger to auto-populate seq_num from sequence if not provided
CREATE OR REPLACE TRIGGER trg_etl_staging_seq
BEFORE INSERT ON etl_staging
FOR EACH ROW
WHEN (NEW.seq_num IS NULL)
BEGIN
    :NEW.seq_num := etl_staging_seq.NEXTVAL;
END;
/

COMMENT ON TABLE etl_staging IS 'Temporary storage for data during ETL processing';

-- ETL error log
CREATE TABLE etl_error_log (
    error_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id VARCHAR2(100),
    seq_num NUMBER,
    error_code VARCHAR2(50),
    error_message VARCHAR2(4000),
    error_detail CLOB,
    occurred_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    
    CONSTRAINT fk_etl_error_session FOREIGN KEY (session_id)
        REFERENCES etl_sessions(session_id) ON DELETE SET NULL
);

CREATE INDEX idx_etl_error_session ON etl_error_log(session_id);
CREATE INDEX idx_etl_error_occurred ON etl_error_log(occurred_at);

COMMENT ON TABLE etl_error_log IS 'Detailed error logging for ETL operations';

-- Transform audit table (used by etl_pkg)
CREATE TABLE transform_audit (
    audit_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id VARCHAR2(100),
    entity_type VARCHAR2(50) NOT NULL,
    entity_id VARCHAR2(100),
    tenant_id VARCHAR2(50),
    transform_type VARCHAR2(50) NOT NULL,
    transform_rule VARCHAR2(200),
    before_value CLOB,
    after_value CLOB,
    transform_timestamp TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    transformed_by VARCHAR2(100),
    
    CONSTRAINT fk_transform_audit_session FOREIGN KEY (session_id)
        REFERENCES etl_sessions(session_id) ON DELETE SET NULL
);

CREATE INDEX idx_transform_audit_session ON transform_audit(session_id);
CREATE INDEX idx_transform_audit_entity ON transform_audit(entity_type, entity_id);
CREATE INDEX idx_transform_audit_tenant ON transform_audit(tenant_id);
CREATE INDEX idx_transform_audit_time ON transform_audit(transform_timestamp);

COMMENT ON TABLE transform_audit IS 'Audit trail for ETL data transformations';

-- =============================================================================
-- PART 11: DOMAIN TABLES
-- =============================================================================

-- Customers table (matches customer_pkg expectations)
CREATE TABLE customers (
    id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id VARCHAR2(50) NOT NULL,
    customer_code VARCHAR2(50) NOT NULL,
    customer_type VARCHAR2(20) NOT NULL,
    name VARCHAR2(200) NOT NULL,
    trade_name VARCHAR2(200),
    tax_id VARCHAR2(20),
    email VARCHAR2(200),
    phone VARCHAR2(50),
    address_line1 VARCHAR2(200),
    address_line2 VARCHAR2(200),
    city VARCHAR2(100),
    state VARCHAR2(50),
    postal_code VARCHAR2(20),
    country VARCHAR2(50),
    active NUMBER(1) DEFAULT 1 NOT NULL,
    notes CLOB,
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    created_by VARCHAR2(100),
    updated_by VARCHAR2(100),
    
    CONSTRAINT chk_customer_type CHECK (customer_type IN ('INDIVIDUAL', 'COMPANY')),
    CONSTRAINT chk_customer_active CHECK (active IN (0, 1)),
    CONSTRAINT uk_customer_tenant_code UNIQUE (tenant_id, customer_code)
);

CREATE INDEX idx_customers_tenant ON customers(tenant_id);
CREATE INDEX idx_customers_tenant_code ON customers(tenant_id, customer_code);
CREATE INDEX idx_customers_tax_id ON customers(tenant_id, tax_id);

COMMENT ON TABLE customers IS 'Customer master data';

-- Contracts table (matches contract_pkg expectations)
CREATE TABLE contracts (
    id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id VARCHAR2(50) NOT NULL,
    contract_number VARCHAR2(50) NOT NULL,
    contract_type VARCHAR2(20) NOT NULL,
    customer_id NUMBER NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE,
    duration_months NUMBER,
    auto_renew NUMBER(1) DEFAULT 0 NOT NULL,
    total_value NUMBER(15,2) DEFAULT 0 NOT NULL,
    payment_terms VARCHAR2(500),
    billing_cycle VARCHAR2(20),
    status VARCHAR2(20) NOT NULL,
    signed_at TIMESTAMP,
    signed_by VARCHAR2(100),
    notes CLOB,
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    created_by VARCHAR2(100),
    updated_by VARCHAR2(100),
    
    CONSTRAINT uk_contract_tenant_number UNIQUE (tenant_id, contract_number),
    CONSTRAINT chk_contract_type CHECK (contract_type IN ('SERVICE', 'RECURRING', 'PROJECT')),
    CONSTRAINT chk_contract_status CHECK (status IN ('DRAFT', 'PENDING', 'ACTIVE', 'SUSPENDED', 'CANCELLED', 'COMPLETED')),
    CONSTRAINT chk_contract_auto_renew CHECK (auto_renew IN (0, 1)),
    CONSTRAINT fk_contract_customer FOREIGN KEY (customer_id) REFERENCES customers(id)
);

CREATE INDEX idx_contracts_tenant ON contracts(tenant_id);
CREATE INDEX idx_contracts_customer ON contracts(tenant_id, customer_id);
CREATE INDEX idx_contracts_status ON contracts(tenant_id, status);

COMMENT ON TABLE contracts IS 'Contract master data';

-- Contract items table (matches contract_pkg expectations)
CREATE TABLE contract_items (
    id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id VARCHAR2(50) NOT NULL,
    contract_id NUMBER NOT NULL,
    item_name VARCHAR2(200),
    description VARCHAR2(500),
    quantity NUMBER(12,2) DEFAULT 0 NOT NULL,
    unit_price NUMBER(15,2) DEFAULT 0 NOT NULL,
    discount_pct NUMBER(5,2) DEFAULT 0 NOT NULL,
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    created_by VARCHAR2(100),
    updated_by VARCHAR2(100),
    
    CONSTRAINT fk_contract_items_contract FOREIGN KEY (contract_id) REFERENCES contracts(id) ON DELETE CASCADE
);

CREATE INDEX idx_contract_items_contract ON contract_items(tenant_id, contract_id);

COMMENT ON TABLE contract_items IS 'Line items within contracts';

-- Contract status history (tracks status transitions)
CREATE TABLE contract_status_history (
    id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id VARCHAR2(50) NOT NULL,
    contract_id NUMBER NOT NULL,
    previous_status VARCHAR2(20),
    new_status VARCHAR2(20) NOT NULL,
    changed_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    changed_by VARCHAR2(100),
    change_reason VARCHAR2(500),
    
    CONSTRAINT fk_csh_contract FOREIGN KEY (contract_id) REFERENCES contracts(id) ON DELETE CASCADE
);

CREATE INDEX idx_csh_contract ON contract_status_history(tenant_id, contract_id);
CREATE INDEX idx_csh_changed_at ON contract_status_history(changed_at);

COMMENT ON TABLE contract_status_history IS 'Audit trail of contract status changes';

-- =============================================================================
-- PART 12: SUPPORTING TABLES
-- =============================================================================

-- Error catalog - standardized error codes
CREATE TABLE error_catalog (
    error_code VARCHAR2(50) PRIMARY KEY,
    error_category VARCHAR2(50) NOT NULL,
    error_name VARCHAR2(200) NOT NULL,
    description VARCHAR2(1000),
    
    -- Behavior
    retryable NUMBER(1) DEFAULT 0,
    severity VARCHAR2(20) NOT NULL,
    
    -- Recommendations
    troubleshooting_guide CLOB,
    resolution_steps CLOB,
    
    -- Classification
    is_technical NUMBER(1) DEFAULT 1,
    is_business NUMBER(1) DEFAULT 0,
    
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    
    CONSTRAINT chk_error_category CHECK (error_category IN (
        'VALIDATION', 'TRANSFORMATION', 'ROUTING', 'CONNECTION', 'TIMEOUT', 
        'AUTHENTICATION', 'AUTHORIZATION', 'BUSINESS_LOGIC', 'DATA_QUALITY', 'SYSTEM'
    )),
    CONSTRAINT chk_error_severity CHECK (severity IN ('INFO', 'WARNING', 'ERROR', 'CRITICAL'))
);

COMMENT ON TABLE error_catalog IS 'Standardized error code catalog for consistent error handling';

-- Integration partners
CREATE TABLE integration_partners (
    partner_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    partner_code VARCHAR2(50) NOT NULL UNIQUE,
    partner_name VARCHAR2(200) NOT NULL,
    
    -- Contact
    primary_contact_name VARCHAR2(200),
    primary_contact_email VARCHAR2(200),
    primary_contact_phone VARCHAR2(50),
    
    -- SLA
    sla_tier VARCHAR2(20) DEFAULT 'STANDARD',
    response_time_sla_minutes NUMBER,
    availability_sla_percent NUMBER,
    
    -- Integration details
    supported_protocols VARCHAR2(200),
    preferred_format VARCHAR2(50),
    api_version VARCHAR2(20),
    
    -- Status
    status VARCHAR2(20) DEFAULT 'ACTIVE',
    onboarded_at TIMESTAMP,
    offboarded_at TIMESTAMP,
    
    -- Metadata
    notes CLOB,
    tags CLOB CHECK (tags IS JSON),
    
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    created_by VARCHAR2(100),
    
    CONSTRAINT chk_partner_status CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'OFFBOARDED'))
);

CREATE INDEX idx_partner_code ON integration_partners(partner_code, status);

COMMENT ON TABLE integration_partners IS 'Registry of integration partners and their configurations';

-- Schema version tracking
CREATE TABLE schema_versions (
    version_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    version_number VARCHAR2(20) NOT NULL UNIQUE,
    description VARCHAR2(500),
    migration_script CLOB,
    applied_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    applied_by VARCHAR2(100)
);

COMMENT ON TABLE schema_versions IS 'Schema version history for migrations';

-- Insert initial version
INSERT INTO schema_versions (version_number, description, applied_by)
VALUES ('2.0.0', 'Enhanced integration management schema with comprehensive EIP support', 'SYSTEM');

-- =============================================================================
-- PART 13: LEGACY COMPATIBILITY TABLES FOR INTEGRATION_PKG
-- These tables support the integration_pkg package procedures
-- =============================================================================

-- Integration logs for autonomous error logging
CREATE TABLE integration_logs (
    log_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message VARCHAR2(4000),
    error_details VARCHAR2(4000),
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE INDEX idx_integration_logs_created ON integration_logs(created_at DESC);

COMMENT ON TABLE integration_logs IS 'Integration error and diagnostic logging';

-- Routing rules for content-based message routing
CREATE TABLE routing_rules (
    rule_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_name VARCHAR2(100) NOT NULL,
    rule_set VARCHAR2(50) DEFAULT 'DEFAULT',
    message_type VARCHAR2(50),
    condition_expression VARCHAR2(1000),
    destination VARCHAR2(200) NOT NULL,
    priority NUMBER DEFAULT 100,
    active NUMBER(1) DEFAULT 1,
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE INDEX idx_routing_rules_set ON routing_rules(rule_set, priority);
CREATE INDEX idx_routing_rules_type ON routing_rules(message_type, active);

COMMENT ON TABLE routing_rules IS 'Content-based routing rules configuration';

-- Message deduplication tracking
CREATE TABLE message_dedup (
    dedup_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id VARCHAR2(100) NOT NULL,
    message_hash VARCHAR2(64),
    tenant_id VARCHAR2(50),
    first_seen TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    last_seen TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    process_count NUMBER DEFAULT 1,
    
    CONSTRAINT uk_message_dedup_id UNIQUE (message_id)
);

CREATE INDEX idx_message_dedup_hash ON message_dedup(message_hash, tenant_id);
CREATE INDEX idx_message_dedup_seen ON message_dedup(last_seen);

COMMENT ON TABLE message_dedup IS 'Message deduplication tracking for idempotency';

-- Integration messages for message lifecycle management
CREATE TABLE integration_messages (
    message_id VARCHAR2(100) PRIMARY KEY,
    correlation_id VARCHAR2(100),
    message_type VARCHAR2(50) NOT NULL,
    source_system VARCHAR2(100),
    routing_key VARCHAR2(200),
    destination VARCHAR2(200),
    payload CLOB,
    status VARCHAR2(20) DEFAULT 'PENDING' NOT NULL,
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    processed_at TIMESTAMP,
    retry_count NUMBER DEFAULT 0,
    max_retries NUMBER DEFAULT 3,
    next_retry_at TIMESTAMP,
    error_message VARCHAR2(4000),
    
    CONSTRAINT chk_integ_msg_status CHECK (status IN (
        'PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DEAD_LETTER'
    ))
);

CREATE INDEX idx_integ_msg_status ON integration_messages(status, created_at);
CREATE INDEX idx_integ_msg_correlation ON integration_messages(correlation_id);
CREATE INDEX idx_integ_msg_type ON integration_messages(message_type, status);

COMMENT ON TABLE integration_messages IS 'Integration message lifecycle management';

-- Message aggregation for collecting correlated messages
CREATE TABLE message_aggregation (
    aggregation_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    correlation_id VARCHAR2(100) NOT NULL,
    aggregation_key VARCHAR2(100) NOT NULL,
    expected_count NUMBER,
    current_count NUMBER DEFAULT 0,
    aggregated_result CLOB,
    status VARCHAR2(20) DEFAULT 'PENDING' NOT NULL,
    started_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    timeout_at TIMESTAMP,
    completed_at TIMESTAMP,
    
    CONSTRAINT uk_msg_agg_corr_key UNIQUE (correlation_id, aggregation_key),
    CONSTRAINT chk_msg_agg_status CHECK (status IN (
        'PENDING', 'COMPLETE', 'TIMEOUT', 'CANCELLED'
    ))
);

CREATE INDEX idx_msg_agg_status ON message_aggregation(status, timeout_at);
CREATE INDEX idx_msg_agg_correlation ON message_aggregation(correlation_id);

COMMENT ON TABLE message_aggregation IS 'Message aggregation for correlated message collection';

COMMIT;

PROMPT =====================================================
PROMPT All tables created successfully.
PROMPT =====================================================
