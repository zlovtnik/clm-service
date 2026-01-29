-- =============================================================================
-- PACKAGE: USER_PKG
-- Purpose: User management and OAuth integration
-- Author: GprintEx Team
-- Date: 2026-01-28
-- =============================================================================

CREATE OR REPLACE PACKAGE user_pkg AS
    
    -- =========================================================================
    -- USER SYNC FROM IDP (Keycloak/Oracle OAuth)
    -- =========================================================================
    
    /**
     * Sync or create user from IdP claims (called on login/token validation)
     * This is the main entry point for JWT-based user sync
     * 
     * @param p_user_id     IdP subject claim (sub)
     * @param p_email       User email from token
     * @param p_first_name  First name claim
     * @param p_last_name   Last name claim
     * @param p_username    Preferred username claim
     * @param p_tenant_id   Tenant from token or header
     * @param p_idp_source  Identity provider (KEYCLOAK, ORACLE_ORDS, etc.)
     * @param p_claims      Full JWT claims as JSON (optional)
     * @return              User ID (same as p_user_id)
     */
    FUNCTION sync_user_from_idp(
        p_user_id       IN VARCHAR2,
        p_email         IN VARCHAR2,
        p_first_name    IN VARCHAR2 DEFAULT NULL,
        p_last_name     IN VARCHAR2 DEFAULT NULL,
        p_username      IN VARCHAR2 DEFAULT NULL,
        p_tenant_id     IN VARCHAR2,
        p_idp_source    IN VARCHAR2 DEFAULT 'KEYCLOAK',
        p_claims        IN CLOB DEFAULT NULL
    ) RETURN VARCHAR2;
    
    /**
     * Record user login event
     */
    PROCEDURE record_login(
        p_user_id       IN VARCHAR2,
        p_tenant_id     IN VARCHAR2,
        p_session_id    IN VARCHAR2 DEFAULT NULL,
        p_ip_address    IN VARCHAR2 DEFAULT NULL,
        p_user_agent    IN VARCHAR2 DEFAULT NULL
    );
    
    /**
     * Record user logout event
     */
    PROCEDURE record_logout(
        p_user_id       IN VARCHAR2,
        p_session_id    IN VARCHAR2 DEFAULT NULL
    );
    
    -- =========================================================================
    -- USER MANAGEMENT
    -- =========================================================================
    
    /**
     * Get user by ID
     */
    FUNCTION get_user_by_id(
        p_user_id   IN VARCHAR2
    ) RETURN SYS_REFCURSOR;
    
    /**
     * Get user by email
     */
    FUNCTION get_user_by_email(
        p_email     IN VARCHAR2
    ) RETURN SYS_REFCURSOR;
    
    /**
     * Update user profile
     */
    PROCEDURE update_user_profile(
        p_user_id       IN VARCHAR2,
        p_first_name    IN VARCHAR2 DEFAULT NULL,
        p_last_name     IN VARCHAR2 DEFAULT NULL,
        p_display_name  IN VARCHAR2 DEFAULT NULL,
        p_phone_number  IN VARCHAR2 DEFAULT NULL,
        p_locale        IN VARCHAR2 DEFAULT NULL,
        p_timezone      IN VARCHAR2 DEFAULT NULL,
        p_updated_by    IN VARCHAR2 DEFAULT 'SYSTEM'
    );
    
    /**
     * Lock user account
     */
    PROCEDURE lock_user(
        p_user_id       IN VARCHAR2,
        p_reason        IN VARCHAR2,
        p_locked_by     IN VARCHAR2 DEFAULT 'SYSTEM'
    );
    
    /**
     * Unlock user account
     */
    PROCEDURE unlock_user(
        p_user_id       IN VARCHAR2,
        p_unlocked_by   IN VARCHAR2 DEFAULT 'SYSTEM'
    );
    
    -- =========================================================================
    -- TENANT ACCESS
    -- =========================================================================
    
    /**
     * Grant user access to tenant
     */
    PROCEDURE grant_tenant_access(
        p_user_id       IN VARCHAR2,
        p_tenant_id     IN VARCHAR2,
        p_role          IN VARCHAR2 DEFAULT 'USER',
        p_granted_by    IN VARCHAR2 DEFAULT 'SYSTEM',
        p_expires_at    IN TIMESTAMP DEFAULT NULL
    );
    
    /**
     * Revoke user access from tenant
     */
    PROCEDURE revoke_tenant_access(
        p_user_id       IN VARCHAR2,
        p_tenant_id     IN VARCHAR2,
        p_revoked_by    IN VARCHAR2 DEFAULT 'SYSTEM'
    );
    
    /**
     * Check if user has access to tenant
     */
    FUNCTION has_tenant_access(
        p_user_id       IN VARCHAR2,
        p_tenant_id     IN VARCHAR2,
        p_required_role IN VARCHAR2 DEFAULT NULL
    ) RETURN NUMBER;  -- 1 = has access, 0 = no access
    
    /**
     * Get user's tenant list with roles
     */
    FUNCTION get_user_tenants(
        p_user_id   IN VARCHAR2
    ) RETURN SYS_REFCURSOR;
    
    -- =========================================================================
    -- AUDIT
    -- =========================================================================
    
    /**
     * Log security event
     */
    PROCEDURE log_security_event(
        p_user_id       IN VARCHAR2,
        p_tenant_id     IN VARCHAR2 DEFAULT NULL,
        p_event_type    IN VARCHAR2,
        p_event_details IN CLOB DEFAULT NULL,
        p_ip_address    IN VARCHAR2 DEFAULT NULL,
        p_user_agent    IN VARCHAR2 DEFAULT NULL,
        p_session_id    IN VARCHAR2 DEFAULT NULL
    );
    
    /**
     * Get user audit history
     */
    FUNCTION get_user_audit_history(
        p_user_id       IN VARCHAR2,
        p_from_date     IN TIMESTAMP DEFAULT NULL,
        p_to_date       IN TIMESTAMP DEFAULT NULL,
        p_event_types   IN VARCHAR2 DEFAULT NULL,  -- comma-separated
        p_limit         IN NUMBER DEFAULT 100
    ) RETURN SYS_REFCURSOR;

END user_pkg;
/

CREATE OR REPLACE PACKAGE BODY user_pkg AS

    -- =========================================================================
    -- SYNC USER FROM IDP
    -- =========================================================================
    
    FUNCTION sync_user_from_idp(
        p_user_id       IN VARCHAR2,
        p_email         IN VARCHAR2,
        p_first_name    IN VARCHAR2 DEFAULT NULL,
        p_last_name     IN VARCHAR2 DEFAULT NULL,
        p_username      IN VARCHAR2 DEFAULT NULL,
        p_tenant_id     IN VARCHAR2,
        p_idp_source    IN VARCHAR2 DEFAULT 'KEYCLOAK',
        p_claims        IN CLOB DEFAULT NULL
    ) RETURN VARCHAR2 IS
        v_exists NUMBER;
        v_display_name VARCHAR2(200);
        v_at_pos NUMBER;
    BEGIN
        -- Input validation
        IF p_user_id IS NULL OR TRIM(p_user_id) = '' THEN
            raise_application_error(-20001, 'sync_user_from_idp: p_user_id is required');
        END IF;
        IF p_tenant_id IS NULL OR TRIM(p_tenant_id) = '' THEN
            raise_application_error(-20002, 'sync_user_from_idp: p_tenant_id is required');
        END IF;
        IF p_email IS NULL OR TRIM(p_email) = '' THEN
            raise_application_error(-20003, 'sync_user_from_idp: p_email is required');
        END IF;
        
        -- Build display name safely
        v_display_name := TRIM(NVL(p_first_name, '') || ' ' || NVL(p_last_name, ''));
        IF v_display_name IS NULL OR LENGTH(TRIM(v_display_name)) = 0 THEN
            -- Safe extraction of email prefix
            v_at_pos := INSTR(p_email, '@');
            IF v_at_pos > 1 THEN
                v_display_name := NVL(p_username, SUBSTR(p_email, 1, v_at_pos - 1));
            ELSE
                v_display_name := NVL(p_username, p_email);
            END IF;
        END IF;
        
        -- Check if user exists
        SELECT COUNT(*) INTO v_exists FROM user_accounts WHERE user_id = p_user_id;
        
        IF v_exists = 0 THEN
            -- Insert new user
            INSERT INTO user_accounts (
                user_id, email, username, first_name, last_name, display_name,
                primary_tenant_id, idp_source, idp_user_id, 
                last_token_issued_at, last_login_at, status, email_verified
            ) VALUES (
                p_user_id, LOWER(p_email), p_username, p_first_name, p_last_name, v_display_name,
                p_tenant_id, p_idp_source, p_user_id,
                SYSTIMESTAMP, SYSTIMESTAMP, 'ACTIVE', 1
            );
            
            -- Grant access to primary tenant
            grant_tenant_access(
                p_user_id    => p_user_id,
                p_tenant_id  => p_tenant_id,
                p_role       => 'USER',
                p_granted_by => 'SYSTEM'
            );
            
            -- Log account creation
            log_security_event(
                p_user_id       => p_user_id,
                p_tenant_id     => p_tenant_id,
                p_event_type    => 'ACCOUNT_CREATED',
                p_event_details => p_claims
            );
        ELSE
            -- Update existing user with latest IdP data
            UPDATE user_accounts SET
                email = LOWER(p_email),
                username = NVL(p_username, username),
                first_name = NVL(p_first_name, first_name),
                last_name = NVL(p_last_name, last_name),
                display_name = NVL(v_display_name, display_name),
                last_token_issued_at = SYSTIMESTAMP,
                last_login_at = SYSTIMESTAMP,
                email_verified = 1,
                updated_at = SYSTIMESTAMP,
                updated_by = 'IDP_SYNC'
            WHERE user_id = p_user_id;
        END IF;
        
        COMMIT;
        RETURN p_user_id;
    EXCEPTION
        WHEN OTHERS THEN
            ROLLBACK;
            RAISE;
    END sync_user_from_idp;
    
    PROCEDURE record_login(
        p_user_id       IN VARCHAR2,
        p_tenant_id     IN VARCHAR2,
        p_session_id    IN VARCHAR2 DEFAULT NULL,
        p_ip_address    IN VARCHAR2 DEFAULT NULL,
        p_user_agent    IN VARCHAR2 DEFAULT NULL
    ) IS
    BEGIN
        -- Update last login
        UPDATE user_accounts SET
            last_login_at = SYSTIMESTAMP,
            updated_at = SYSTIMESTAMP
        WHERE user_id = p_user_id;
        
        -- Create session record if session_id provided
        IF p_session_id IS NOT NULL THEN
            INSERT INTO user_sessions (
                session_id, user_id, tenant_id, ip_address, user_agent, started_at
            ) VALUES (
                p_session_id, p_user_id, p_tenant_id, p_ip_address, p_user_agent, SYSTIMESTAMP
            );
        END IF;
        
        -- Log event
        log_security_event(
            p_user_id       => p_user_id,
            p_tenant_id     => p_tenant_id,
            p_event_type    => 'LOGIN',
            p_ip_address    => p_ip_address,
            p_user_agent    => p_user_agent,
            p_session_id    => p_session_id
        );
        
        COMMIT;
    END record_login;
    
    PROCEDURE record_logout(
        p_user_id       IN VARCHAR2,
        p_session_id    IN VARCHAR2 DEFAULT NULL
    ) IS
    BEGIN
        -- End session if provided
        IF p_session_id IS NOT NULL THEN
            UPDATE user_sessions SET
                ended_at = SYSTIMESTAMP,
                end_reason = 'LOGOUT'
            WHERE session_id = p_session_id AND ended_at IS NULL;
        END IF;
        
        -- Log event
        log_security_event(
            p_user_id       => p_user_id,
            p_event_type    => 'LOGOUT',
            p_session_id    => p_session_id
        );
        
        COMMIT;
    END record_logout;
    
    -- =========================================================================
    -- USER MANAGEMENT
    -- =========================================================================
    
    FUNCTION get_user_by_id(
        p_user_id   IN VARCHAR2
    ) RETURN SYS_REFCURSOR IS
        v_cursor SYS_REFCURSOR;
    BEGIN
        OPEN v_cursor FOR
            SELECT u.*, 
                   (SELECT JSON_ARRAYAGG(
                       JSON_OBJECT(
                           'tenant_id' VALUE uta.tenant_id,
                           'role' VALUE uta.role,
                           'granted_at' VALUE uta.granted_at
                       )
                   ) FROM user_tenant_access uta 
                    WHERE uta.user_id = u.user_id AND uta.active = 1
                   ) AS tenants_json
            FROM user_accounts u
            WHERE u.user_id = p_user_id;
        RETURN v_cursor;
    END get_user_by_id;
    
    FUNCTION get_user_by_email(
        p_email     IN VARCHAR2
    ) RETURN SYS_REFCURSOR IS
        v_cursor SYS_REFCURSOR;
    BEGIN
        OPEN v_cursor FOR
            SELECT * FROM user_accounts WHERE LOWER(email) = LOWER(p_email);
        RETURN v_cursor;
    END get_user_by_email;
    
    PROCEDURE update_user_profile(
        p_user_id       IN VARCHAR2,
        p_first_name    IN VARCHAR2 DEFAULT NULL,
        p_last_name     IN VARCHAR2 DEFAULT NULL,
        p_display_name  IN VARCHAR2 DEFAULT NULL,
        p_phone_number  IN VARCHAR2 DEFAULT NULL,
        p_locale        IN VARCHAR2 DEFAULT NULL,
        p_timezone      IN VARCHAR2 DEFAULT NULL,
        p_updated_by    IN VARCHAR2 DEFAULT 'SYSTEM'
    ) IS
    BEGIN
        UPDATE user_accounts SET
            first_name = NVL(p_first_name, first_name),
            last_name = NVL(p_last_name, last_name),
            display_name = NVL(p_display_name, display_name),
            phone_number = NVL(p_phone_number, phone_number),
            locale = NVL(p_locale, locale),
            timezone = NVL(p_timezone, timezone),
            updated_by = p_updated_by,
            updated_at = SYSTIMESTAMP
        WHERE user_id = p_user_id;
        
        log_security_event(
            p_user_id    => p_user_id,
            p_event_type => 'ACCOUNT_UPDATED'
        );
        
        COMMIT;
    END update_user_profile;
    
    PROCEDURE lock_user(
        p_user_id       IN VARCHAR2,
        p_reason        IN VARCHAR2,
        p_locked_by     IN VARCHAR2 DEFAULT 'SYSTEM'
    ) IS
    BEGIN
        UPDATE user_accounts SET
            status = 'LOCKED',
            locked_at = SYSTIMESTAMP,
            lock_reason = p_reason,
            updated_by = p_locked_by,
            updated_at = SYSTIMESTAMP
        WHERE user_id = p_user_id;
        
        log_security_event(
            p_user_id       => p_user_id,
            p_event_type    => 'ACCOUNT_LOCKED',
            p_event_details => JSON_OBJECT('reason' VALUE p_reason, 'locked_by' VALUE p_locked_by)
        );
        
        COMMIT;
    END lock_user;
    
    PROCEDURE unlock_user(
        p_user_id       IN VARCHAR2,
        p_unlocked_by   IN VARCHAR2 DEFAULT 'SYSTEM'
    ) IS
    BEGIN
        UPDATE user_accounts SET
            status = 'ACTIVE',
            locked_at = NULL,
            lock_reason = NULL,
            updated_by = p_unlocked_by,
            updated_at = SYSTIMESTAMP
        WHERE user_id = p_user_id;
        
        log_security_event(
            p_user_id       => p_user_id,
            p_event_type    => 'ACCOUNT_UNLOCKED',
            p_event_details => JSON_OBJECT('unlocked_by' VALUE p_unlocked_by)
        );
        
        COMMIT;
    END unlock_user;
    
    -- =========================================================================
    -- TENANT ACCESS
    -- =========================================================================
    
    PROCEDURE grant_tenant_access(
        p_user_id       IN VARCHAR2,
        p_tenant_id     IN VARCHAR2,
        p_role          IN VARCHAR2 DEFAULT 'USER',
        p_granted_by    IN VARCHAR2 DEFAULT 'SYSTEM',
        p_expires_at    IN TIMESTAMP DEFAULT NULL
    ) IS
    BEGIN
        MERGE INTO user_tenant_access uta
        USING (SELECT p_user_id AS user_id, p_tenant_id AS tenant_id FROM DUAL) src
        ON (uta.user_id = src.user_id AND uta.tenant_id = src.tenant_id)
        WHEN MATCHED THEN
            UPDATE SET 
                role = p_role,
                active = 1,
                granted_at = SYSTIMESTAMP,
                granted_by = p_granted_by,
                expires_at = p_expires_at,
                revoked_at = NULL,
                revoked_by = NULL
        WHEN NOT MATCHED THEN
            INSERT (user_id, tenant_id, role, granted_at, granted_by, expires_at, active)
            VALUES (p_user_id, p_tenant_id, p_role, SYSTIMESTAMP, p_granted_by, p_expires_at, 1);
        
        log_security_event(
            p_user_id       => p_user_id,
            p_tenant_id     => p_tenant_id,
            p_event_type    => 'TENANT_ACCESS_GRANTED',
            p_event_details => JSON_OBJECT('role' VALUE p_role, 'granted_by' VALUE p_granted_by)
        );
        
        COMMIT;
    END grant_tenant_access;
    
    PROCEDURE revoke_tenant_access(
        p_user_id       IN VARCHAR2,
        p_tenant_id     IN VARCHAR2,
        p_revoked_by    IN VARCHAR2 DEFAULT 'SYSTEM'
    ) IS
    BEGIN
        UPDATE user_tenant_access SET
            active = 0,
            revoked_at = SYSTIMESTAMP,
            revoked_by = p_revoked_by
        WHERE user_id = p_user_id AND tenant_id = p_tenant_id;
        
        log_security_event(
            p_user_id       => p_user_id,
            p_tenant_id     => p_tenant_id,
            p_event_type    => 'TENANT_ACCESS_REVOKED',
            p_event_details => JSON_OBJECT('revoked_by' VALUE p_revoked_by)
        );
        
        COMMIT;
    END revoke_tenant_access;
    
    FUNCTION has_tenant_access(
        p_user_id       IN VARCHAR2,
        p_tenant_id     IN VARCHAR2,
        p_required_role IN VARCHAR2 DEFAULT NULL
    ) RETURN NUMBER IS
        v_count NUMBER;
        v_role_rank NUMBER;
        v_required_rank NUMBER;
    BEGIN
        -- Role ranking: VIEWER < USER < ADMIN < OWNER < SUPERADMIN
        IF p_required_role IS NOT NULL THEN
            SELECT CASE p_required_role
                WHEN 'VIEWER' THEN 1
                WHEN 'USER' THEN 2
                WHEN 'ADMIN' THEN 3
                WHEN 'OWNER' THEN 4
                WHEN 'SUPERADMIN' THEN 5
                ELSE 0
            END INTO v_required_rank FROM DUAL;
            
            SELECT COUNT(*) INTO v_count
            FROM user_tenant_access
            WHERE user_id = p_user_id 
              AND tenant_id = p_tenant_id 
              AND active = 1
              AND (expires_at IS NULL OR expires_at > SYSTIMESTAMP)
              AND CASE role
                    WHEN 'VIEWER' THEN 1
                    WHEN 'USER' THEN 2
                    WHEN 'ADMIN' THEN 3
                    WHEN 'OWNER' THEN 4
                    WHEN 'SUPERADMIN' THEN 5
                    ELSE 0
                  END >= v_required_rank;
        ELSE
            SELECT COUNT(*) INTO v_count
            FROM user_tenant_access
            WHERE user_id = p_user_id 
              AND tenant_id = p_tenant_id 
              AND active = 1
              AND (expires_at IS NULL OR expires_at > SYSTIMESTAMP);
        END IF;
        
        RETURN CASE WHEN v_count > 0 THEN 1 ELSE 0 END;
    END has_tenant_access;
    
    FUNCTION get_user_tenants(
        p_user_id   IN VARCHAR2
    ) RETURN SYS_REFCURSOR IS
        v_cursor SYS_REFCURSOR;
    BEGIN
        OPEN v_cursor FOR
            SELECT tenant_id, role, granted_at, expires_at
            FROM user_tenant_access
            WHERE user_id = p_user_id 
              AND active = 1
              AND (expires_at IS NULL OR expires_at > SYSTIMESTAMP)
            ORDER BY granted_at;
        RETURN v_cursor;
    END get_user_tenants;
    
    -- =========================================================================
    -- AUDIT
    -- =========================================================================
    
    PROCEDURE log_security_event(
        p_user_id       IN VARCHAR2,
        p_tenant_id     IN VARCHAR2 DEFAULT NULL,
        p_event_type    IN VARCHAR2,
        p_event_details IN CLOB DEFAULT NULL,
        p_ip_address    IN VARCHAR2 DEFAULT NULL,
        p_user_agent    IN VARCHAR2 DEFAULT NULL,
        p_session_id    IN VARCHAR2 DEFAULT NULL
    ) IS
        PRAGMA AUTONOMOUS_TRANSACTION;
    BEGIN
        INSERT INTO user_audit_log (
            user_id, tenant_id, event_type, event_details, 
            ip_address, user_agent, session_id, event_at
        ) VALUES (
            p_user_id, p_tenant_id, p_event_type, p_event_details,
            p_ip_address, p_user_agent, p_session_id, SYSTIMESTAMP
        );
        COMMIT;
    EXCEPTION
        WHEN OTHERS THEN
            ROLLBACK;
            -- Don't raise - audit logging should not break main flow
            NULL;
    END log_security_event;
    
    FUNCTION get_user_audit_history(
        p_user_id       IN VARCHAR2,
        p_from_date     IN TIMESTAMP DEFAULT NULL,
        p_to_date       IN TIMESTAMP DEFAULT NULL,
        p_event_types   IN VARCHAR2 DEFAULT NULL,
        p_limit         IN NUMBER DEFAULT 100
    ) RETURN SYS_REFCURSOR IS
        v_cursor SYS_REFCURSOR;
    BEGIN
        OPEN v_cursor FOR
            SELECT * FROM (
                SELECT audit_id, user_id, tenant_id, event_type, event_details,
                       ip_address, user_agent, session_id, event_at
                FROM user_audit_log
                WHERE user_id = p_user_id
                  AND (p_from_date IS NULL OR event_at >= p_from_date)
                  AND (p_to_date IS NULL OR event_at <= p_to_date)
                  AND (p_event_types IS NULL OR INSTR(',' || p_event_types || ',', ',' || event_type || ',') > 0)
                ORDER BY event_at DESC
            ) WHERE ROWNUM <= p_limit;
        RETURN v_cursor;
    END get_user_audit_history;

END user_pkg;
/

SHOW ERRORS;

-- =============================================================================
-- ORDS REST API Registration for user_pkg
-- =============================================================================
BEGIN
    ORDS.ENABLE_OBJECT(
        p_enabled       => TRUE,
        p_schema        => USER,
        p_object        => 'USER_PKG',
        p_object_type   => 'PACKAGE'
    );
    COMMIT;
END;
/

PROMPT user_pkg created and exposed via ORDS.
