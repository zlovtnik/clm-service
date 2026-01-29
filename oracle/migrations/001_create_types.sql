-- =============================================================================
-- MIGRATION 001: CREATE ORACLE TYPES
-- Purpose: Define Oracle object types for domain models
-- Author: GprintEx Team
-- Date: 2026-01-28
-- =============================================================================

-- =============================================================================
-- CUSTOMER TYPE
-- Matches structure expected by customer_pkg
-- =============================================================================
CREATE OR REPLACE TYPE customer_t AS OBJECT (
    tenant_id           VARCHAR2(50),
    customer_code       VARCHAR2(50),
    name                VARCHAR2(200),
    id                  NUMBER,
    customer_type       VARCHAR2(20),
    trade_name          VARCHAR2(200),
    tax_id              VARCHAR2(20),
    email               VARCHAR2(200),
    phone               VARCHAR2(50),
    address_line1       VARCHAR2(200),
    address_line2       VARCHAR2(200),
    city                VARCHAR2(100),
    state               VARCHAR2(50),
    postal_code         VARCHAR2(20),
    country             VARCHAR2(50),
    active              NUMBER(1),
    notes               CLOB,
    created_at          TIMESTAMP,
    updated_at          TIMESTAMP,
    created_by          VARCHAR2(100),
    updated_by          VARCHAR2(100),
    
    -- Constructor that only requires tenant_id, customer_code, name
    CONSTRUCTOR FUNCTION customer_t(
        p_tenant_id IN VARCHAR2,
        p_customer_code IN VARCHAR2,
        p_name IN VARCHAR2
    ) RETURN SELF AS RESULT
);
/

CREATE OR REPLACE TYPE BODY customer_t AS
    CONSTRUCTOR FUNCTION customer_t(
        p_tenant_id IN VARCHAR2,
        p_customer_code IN VARCHAR2,
        p_name IN VARCHAR2
    ) RETURN SELF AS RESULT IS
    BEGIN
        SELF.tenant_id := p_tenant_id;
        SELF.customer_code := p_customer_code;
        SELF.name := p_name;
        RETURN;
    END;
END;
/

CREATE OR REPLACE TYPE customer_tab AS TABLE OF customer_t;
/

-- =============================================================================
-- CONTRACT ITEM TYPE
-- =============================================================================
CREATE OR REPLACE TYPE contract_item_t AS OBJECT (
    item_id             VARCHAR2(100),
    contract_id         VARCHAR2(100),
    product_code        VARCHAR2(50),
    product_name        VARCHAR2(200),
    quantity            NUMBER,
    unit_price          NUMBER(15,2),
    total_price         NUMBER(15,2),
    discount_percent    NUMBER(5,2),
    start_date          DATE,
    end_date            DATE,
    status              VARCHAR2(20)
);
/

-- =============================================================================
-- CONTRACT TYPE
-- Matches structure expected by contract_pkg and contracts table
-- =============================================================================
CREATE OR REPLACE TYPE contract_t AS OBJECT (
    tenant_id           VARCHAR2(50),
    contract_number     VARCHAR2(50),
    customer_id         NUMBER,
    start_date          DATE,
    id                  NUMBER,
    contract_type       VARCHAR2(20),
    end_date            DATE,
    duration_months     NUMBER,
    auto_renew          NUMBER(1),
    total_value         NUMBER(15,2),
    payment_terms       VARCHAR2(500),
    billing_cycle       VARCHAR2(20),
    status              VARCHAR2(20),
    signed_at           TIMESTAMP,
    signed_by           VARCHAR2(100),
    notes               CLOB,
    created_at          TIMESTAMP,
    updated_at          TIMESTAMP,
    created_by          VARCHAR2(100),
    updated_by          VARCHAR2(100),
    
    -- Constructor that only requires tenant_id, contract_number, customer_id, start_date
    CONSTRUCTOR FUNCTION contract_t(
        p_tenant_id IN VARCHAR2,
        p_contract_number IN VARCHAR2,
        p_customer_id IN NUMBER,
        p_start_date IN DATE
    ) RETURN SELF AS RESULT
);
/

CREATE OR REPLACE TYPE BODY contract_t AS
    CONSTRUCTOR FUNCTION contract_t(
        p_tenant_id IN VARCHAR2,
        p_contract_number IN VARCHAR2,
        p_customer_id IN NUMBER,
        p_start_date IN DATE
    ) RETURN SELF AS RESULT IS
    BEGIN
        SELF.tenant_id := p_tenant_id;
        SELF.contract_number := p_contract_number;
        SELF.customer_id := p_customer_id;
        SELF.start_date := p_start_date;
        RETURN;
    END;
END;
/

CREATE OR REPLACE TYPE contract_tab AS TABLE OF contract_t;
/

-- =============================================================================
-- VALIDATION RESULT TYPE
-- =============================================================================
CREATE OR REPLACE TYPE validation_result_t AS OBJECT (
    is_valid            NUMBER(1),       -- 1 = valid, 0 = invalid
    error_code          VARCHAR2(50),
    error_message       VARCHAR2(500),
    seq_num             NUMBER,          -- Staging record sequence number
    field_name          VARCHAR2(100),   -- Optional: specific field with error
    severity            VARCHAR2(20),    -- Optional: ERROR, WARNING, INFO
    suggested_value     VARCHAR2(500)    -- Optional: suggested correction
);
/

CREATE OR REPLACE TYPE validation_result_tab AS TABLE OF validation_result_t;
/

-- Note: Packages that need validation_results_tab should declare:
--   SUBTYPE validation_results_tab IS validation_result_tab;
-- inside their package spec to avoid creating duplicate SQL types

-- =============================================================================
-- TRANSFORM METADATA TYPE
-- Used by bulk operations to track processing statistics
-- =============================================================================
CREATE OR REPLACE TYPE transform_metadata_t AS OBJECT (
    operation_type      VARCHAR2(100),
    batch_id            VARCHAR2(100),
    record_count        NUMBER,
    success_count       NUMBER,
    error_count         NUMBER,
    transform_timestamp TIMESTAMP,
    source_system       VARCHAR2(100),
    source_format       VARCHAR2(50),
    target_format       VARCHAR2(50),
    transformation_id   VARCHAR2(100),
    applied_rules       VARCHAR2(4000),
    
    -- Constructor that takes operation type, initializes counts to 0
    CONSTRUCTOR FUNCTION transform_metadata_t(
        p_operation_type IN VARCHAR2
    ) RETURN SELF AS RESULT
);
/

CREATE OR REPLACE TYPE BODY transform_metadata_t AS
    CONSTRUCTOR FUNCTION transform_metadata_t(
        p_operation_type IN VARCHAR2
    ) RETURN SELF AS RESULT IS
    BEGIN
        SELF.operation_type := p_operation_type;
        SELF.batch_id := RAWTOHEX(SYS_GUID());
        SELF.record_count := 0;
        SELF.success_count := 0;
        SELF.error_count := 0;
        RETURN;
    END;
END;
/

-- =============================================================================
-- ETL STAGING ROW TYPE
-- =============================================================================
CREATE OR REPLACE TYPE etl_staging_row_t AS OBJECT (
    session_id          VARCHAR2(100),
    seq_num             NUMBER,
    entity_type         VARCHAR2(50),
    source_system       VARCHAR2(100),
    raw_data            CLOB,
    transformed_data    CLOB,
    status              VARCHAR2(20),
    error_message       VARCHAR2(4000),
    created_at          TIMESTAMP,
    processed_at        TIMESTAMP
);
/

CREATE OR REPLACE TYPE etl_staging_row_tab AS TABLE OF etl_staging_row_t;
/

-- Note: Packages use etl_staging_tab which is a PL/SQL subtype declared in package specs
-- DO NOT create a separate SQL TYPE for etl_staging_tab as Oracle treats them as incompatible

-- =============================================================================
-- INTEGRATION MESSAGE TYPE
-- Used by integration_pkg for message routing and lifecycle
-- =============================================================================
CREATE OR REPLACE TYPE integration_message_t AS OBJECT (
    message_id          VARCHAR2(100),
    correlation_id      VARCHAR2(100),
    message_type        VARCHAR2(50),
    source_system       VARCHAR2(100),
    routing_key         VARCHAR2(200),
    payload             CLOB,
    status              VARCHAR2(20),
    created_at          TIMESTAMP,
    processed_at        TIMESTAMP,
    retry_count         NUMBER,
    error_message       VARCHAR2(4000)
);
/

CREATE OR REPLACE TYPE integration_message_tab AS TABLE OF integration_message_t;
/

-- Note: COMMIT is not needed here as DDL statements auto-commit

PROMPT =====================================================
PROMPT Types created successfully.
PROMPT =====================================================
