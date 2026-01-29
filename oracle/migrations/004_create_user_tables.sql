-- =============================================================================
-- MIGRATION 004: CREATE USER MANAGEMENT TABLES
-- Purpose: Centralized user management integrated with OAuth/ORDS
-- Author: GprintEx Team
-- Date: 2026-01-28
-- =============================================================================

-- =============================================================================
-- USER_ACCOUNTS: Core user table synced from IdP (Keycloak/Oracle OAuth)
-- =============================================================================
CREATE TABLE user_accounts (
    user_id VARCHAR2(100) PRIMARY KEY,           -- IdP subject/sub claim (UUID from Keycloak or Oracle)
    
    -- Identity
    email VARCHAR2(255) NOT NULL,
    email_verified NUMBER(1) DEFAULT 0,
    username VARCHAR2(100),
    
    -- Profile
    first_name VARCHAR2(100),
    last_name VARCHAR2(100),
    display_name VARCHAR2(200),
    phone_number VARCHAR2(50),
    avatar_url VARCHAR2(500),
    locale VARCHAR2(10) DEFAULT 'en',
    timezone VARCHAR2(50) DEFAULT 'UTC',
    
    -- Multi-tenancy
    primary_tenant_id VARCHAR2(50) NOT NULL,
    
    -- OAuth/IdP metadata
    idp_source VARCHAR2(50) DEFAULT 'KEYCLOAK',  -- 'KEYCLOAK', 'ORACLE_ORDS', 'AZURE_AD', etc.
    idp_user_id VARCHAR2(200),                    -- External ID in the IdP
    last_token_issued_at TIMESTAMP,
    last_login_at TIMESTAMP,
    
    -- Account status
    status VARCHAR2(20) DEFAULT 'ACTIVE' NOT NULL,
    locked_at TIMESTAMP,
    lock_reason VARCHAR2(500),
    
    -- Audit
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    created_by VARCHAR2(100) DEFAULT 'SYSTEM',
    updated_by VARCHAR2(100) DEFAULT 'SYSTEM',
    
    CONSTRAINT chk_user_status CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'LOCKED', 'DELETED')),
    CONSTRAINT chk_idp_source CHECK (idp_source IN ('KEYCLOAK', 'ORACLE_ORDS', 'AZURE_AD', 'OKTA', 'LOCAL'))
);

CREATE UNIQUE INDEX idx_user_email ON user_accounts(LOWER(email));
CREATE INDEX idx_user_tenant ON user_accounts(primary_tenant_id, status);
CREATE INDEX idx_user_idp ON user_accounts(idp_source, idp_user_id);
CREATE INDEX idx_user_last_login ON user_accounts(last_login_at DESC);

COMMENT ON TABLE user_accounts IS 'Centralized user accounts synced from IdP (Keycloak/Oracle OAuth)';
COMMENT ON COLUMN user_accounts.user_id IS 'IdP subject claim - unique user identifier';
COMMENT ON COLUMN user_accounts.idp_source IS 'Identity provider: KEYCLOAK, ORACLE_ORDS, AZURE_AD, OKTA, LOCAL';

-- =============================================================================
-- USER_TENANT_ACCESS: Many-to-many user-tenant relationship
-- =============================================================================
CREATE TABLE user_tenant_access (
    user_id VARCHAR2(100) NOT NULL,
    tenant_id VARCHAR2(50) NOT NULL,
    
    -- Access level
    role VARCHAR2(50) NOT NULL,
    
    -- Validity
    granted_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    granted_by VARCHAR2(100),
    expires_at TIMESTAMP,
    revoked_at TIMESTAMP,
    revoked_by VARCHAR2(100),
    
    -- Status
    active NUMBER(1) DEFAULT 1,
    
    CONSTRAINT pk_user_tenant PRIMARY KEY (user_id, tenant_id),
    CONSTRAINT fk_uta_user FOREIGN KEY (user_id) REFERENCES user_accounts(user_id) ON DELETE CASCADE,
    CONSTRAINT chk_uta_role CHECK (role IN ('VIEWER', 'USER', 'ADMIN', 'OWNER', 'SUPERADMIN'))
);

CREATE INDEX idx_uta_tenant ON user_tenant_access(tenant_id, active);

COMMENT ON TABLE user_tenant_access IS 'User access permissions per tenant';

-- =============================================================================
-- USER_SESSIONS: Active session tracking (optional - for audit)
-- =============================================================================
CREATE TABLE user_sessions (
    session_id VARCHAR2(100) PRIMARY KEY,
    user_id VARCHAR2(100) NOT NULL,
    tenant_id VARCHAR2(50),
    
    -- Session info
    ip_address VARCHAR2(45),
    user_agent VARCHAR2(500),
    device_type VARCHAR2(50),
    
    -- Token tracking
    access_token_hash VARCHAR2(64),
    refresh_token_hash VARCHAR2(64),
    token_issued_at TIMESTAMP,
    token_expires_at TIMESTAMP,
    
    -- Lifecycle
    started_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    last_activity_at TIMESTAMP DEFAULT SYSTIMESTAMP,
    ended_at TIMESTAMP,
    end_reason VARCHAR2(50),
    
    CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES user_accounts(user_id) ON DELETE CASCADE,
    CONSTRAINT chk_end_reason CHECK (end_reason IS NULL OR end_reason IN ('LOGOUT', 'EXPIRED', 'REVOKED', 'ADMIN_TERMINATED'))
);

CREATE INDEX idx_session_user ON user_sessions(user_id, started_at DESC);

-- Virtual column for active sessions (Oracle doesn't support WHERE in CREATE INDEX)
ALTER TABLE user_sessions ADD (
    is_active NUMBER(1) GENERATED ALWAYS AS (CASE WHEN ended_at IS NULL THEN 1 ELSE NULL END) VIRTUAL
);
CREATE INDEX idx_session_active ON user_sessions(is_active, user_id);

COMMENT ON TABLE user_sessions IS 'User session tracking for audit and security';

-- =============================================================================
-- USER_AUDIT_LOG: Security audit trail
-- =============================================================================
CREATE TABLE user_audit_log (
    audit_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id VARCHAR2(100),
    tenant_id VARCHAR2(50),
    
    -- Event
    event_type VARCHAR2(50) NOT NULL,
    event_details CLOB CHECK (event_details IS JSON),
    
    -- Context
    ip_address VARCHAR2(45),
    user_agent VARCHAR2(500),
    session_id VARCHAR2(100),
    
    -- Timestamp
    event_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    
    CONSTRAINT chk_audit_event CHECK (event_type IN (
        'LOGIN', 'LOGOUT', 'LOGIN_FAILED', 'PASSWORD_CHANGE', 'PASSWORD_RESET',
        'ACCOUNT_CREATED', 'ACCOUNT_UPDATED', 'ACCOUNT_LOCKED', 'ACCOUNT_UNLOCKED',
        'TENANT_ACCESS_GRANTED', 'TENANT_ACCESS_REVOKED', 'ROLE_CHANGED',
        'TOKEN_ISSUED', 'TOKEN_REVOKED', 'SESSION_TERMINATED'
    ))
);

CREATE INDEX idx_audit_user ON user_audit_log(user_id, event_at DESC);
CREATE INDEX idx_audit_tenant ON user_audit_log(tenant_id, event_at DESC);
CREATE INDEX idx_audit_event ON user_audit_log(event_type, event_at DESC);

-- Partition by month for better performance (optional - requires partitioning license)
-- PARTITION BY RANGE (event_at) INTERVAL (NUMTOYMINTERVAL(1, 'MONTH'))

COMMENT ON TABLE user_audit_log IS 'Security audit log for user events';

-- =============================================================================
-- ORDS OAuth Integration Table (for Oracle ORDS OAuth users)
-- =============================================================================
CREATE TABLE ords_oauth_clients (
    client_id VARCHAR2(100) PRIMARY KEY,
    client_name VARCHAR2(200) NOT NULL,
    
    -- OAuth configuration
    client_secret_hash VARCHAR2(128),
    grant_types VARCHAR2(500) DEFAULT 'authorization_code,refresh_token',
    redirect_uris CLOB CHECK (redirect_uris IS JSON),
    scopes VARCHAR2(1000) DEFAULT 'openid profile email',
    
    -- Token configuration
    access_token_lifetime_seconds NUMBER DEFAULT 3600,
    refresh_token_lifetime_seconds NUMBER DEFAULT 86400,
    
    -- Metadata
    description VARCHAR2(1000),
    contact_email VARCHAR2(255),
    
    -- Status
    active NUMBER(1) DEFAULT 1,
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    created_by VARCHAR2(100)
);

COMMENT ON TABLE ords_oauth_clients IS 'OAuth2 client applications registered with ORDS';

-- =============================================================================
-- Trigger to update updated_at on user_accounts
-- =============================================================================
CREATE OR REPLACE TRIGGER trg_user_accounts_updated
BEFORE UPDATE ON user_accounts
FOR EACH ROW
BEGIN
    :NEW.updated_at := SYSTIMESTAMP;
END;
/

PROMPT User management tables created successfully.
