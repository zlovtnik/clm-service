package com.gprintex.clm.service;

import com.gprintex.clm.client.OrdsClient;
import io.vavr.control.Either;
import io.vavr.control.Try;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * User management service that syncs users from IdP (Keycloak/Oracle OAuth) to Oracle.
 * 
 * <p>This service integrates with the user_pkg Oracle package to:
 * <ul>
 *   <li>Sync user data on login/token validation</li>
 *   <li>Manage tenant access</li>
 *   <li>Record security events</li>
 * </ul>
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final String PKG_NAME = "user_pkg";

    private final OrdsClient ordsClient;

    public UserService(OrdsClient ordsClient) {
        this.ordsClient = ordsClient;
    }

    /**
     * Sync user from JWT claims to Oracle database.
     * Called automatically on authenticated requests via filter.
     *
     * @param jwt       The validated JWT token
     * @param tenantId  Tenant from header or token
     * @param idpSource Identity provider (KEYCLOAK, ORACLE_ORDS, etc.)
     * @return Either error or the user ID
     */
    public Either<String, String> syncUserFromJwt(Jwt jwt, String tenantId, String idpSource) {
        String userId = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        
        if (email == null || email.isBlank()) {
            log.warn("JWT missing email claim for user {}", userId);
            return Either.left("JWT missing email claim");
        }
        
        String firstName = jwt.getClaimAsString("given_name");
        String lastName = jwt.getClaimAsString("family_name");
        String username = jwt.getClaimAsString("preferred_username");
        
        // Build claims JSON for audit
        String claimsJson = Try.of(() -> {
            Map<String, Object> claims = new HashMap<>(jwt.getClaims());
            // Remove sensitive claims before storing
            claims.remove("access_token");
            claims.remove("refresh_token");
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(claims);
        }).getOrElse("{}");
        
        return syncUser(userId, email, firstName, lastName, username, tenantId, idpSource, claimsJson);
    }

    /**
     * Sync user to Oracle database.
     */
    public Either<String, String> syncUser(
            String userId,
            String email,
            String firstName,
            String lastName,
            String username,
            String tenantId,
            String idpSource,
            String claimsJson
    ) {
        Map<String, Object> params = new HashMap<>();
        params.put("p_user_id", userId);
        params.put("p_email", email);
        params.put("p_first_name", firstName);
        params.put("p_last_name", lastName);
        params.put("p_username", username);
        params.put("p_tenant_id", tenantId);
        params.put("p_idp_source", idpSource != null ? idpSource : "KEYCLOAK");
        if (claimsJson != null) {
            params.put("p_claims", claimsJson);
        }

        return ordsClient.callProcedure(PKG_NAME, "SYNC_USER_FROM_IDP", params)
                .map(json -> {
                    String syncedUserId = json.has("~ret") ? json.get("~ret").asText() : userId;
                    log.debug("Synced user {} with email {}", syncedUserId, maskEmail(email));
                    return syncedUserId;
                })
                .mapLeft(error -> {
                    log.error("Failed to sync user {}: {}", userId, error);
                    return error;
                });
    }

    /**
     * Record user login event.
     */
    public void recordLogin(String userId, String tenantId, String sessionId, 
                           String ipAddress, String userAgent) {
        Map<String, Object> params = new HashMap<>();
        params.put("p_user_id", userId);
        params.put("p_tenant_id", tenantId);
        if (sessionId != null) params.put("p_session_id", sessionId);
        if (ipAddress != null) params.put("p_ip_address", ipAddress);
        if (userAgent != null) params.put("p_user_agent", userAgent);

        ordsClient.callProcedure(PKG_NAME, "RECORD_LOGIN", params)
                .peekLeft(error -> log.warn("Failed to record login for user {}: {}", userId, error));
    }

    /**
     * Record user logout event.
     */
    public void recordLogout(String userId, String sessionId) {
        Map<String, Object> params = new HashMap<>();
        params.put("p_user_id", userId);
        if (sessionId != null) params.put("p_session_id", sessionId);

        ordsClient.callProcedure(PKG_NAME, "RECORD_LOGOUT", params)
                .peekLeft(error -> log.warn("Failed to record logout for user {}: {}", userId, error));
    }

    /**
     * Grant tenant access to a user.
     */
    public Either<String, Boolean> grantTenantAccess(String userId, String tenantId, 
                                                      String role, String grantedBy) {
        Map<String, Object> params = Map.of(
            "p_user_id", userId,
            "p_tenant_id", tenantId,
            "p_role", role != null ? role : "USER",
            "p_granted_by", grantedBy != null ? grantedBy : "SYSTEM"
        );

        return ordsClient.callProcedure(PKG_NAME, "GRANT_TENANT_ACCESS", params)
                .map(json -> true);
    }

    /**
     * Revoke tenant access from a user.
     */
    public Either<String, Boolean> revokeTenantAccess(String userId, String tenantId, String revokedBy) {
        Map<String, Object> params = Map.of(
            "p_user_id", userId,
            "p_tenant_id", tenantId,
            "p_revoked_by", revokedBy != null ? revokedBy : "SYSTEM"
        );

        return ordsClient.callProcedure(PKG_NAME, "REVOKE_TENANT_ACCESS", params)
                .map(json -> true);
    }

    /**
     * Check if user has access to tenant with optional role requirement.
     */
    public Either<String, Boolean> hasTenantAccess(String userId, String tenantId, String requiredRole) {
        Map<String, Object> params = new HashMap<>();
        params.put("p_user_id", userId);
        params.put("p_tenant_id", tenantId);
        if (requiredRole != null) {
            params.put("p_required_role", requiredRole);
        }

        return ordsClient.callProcedure(PKG_NAME, "HAS_TENANT_ACCESS", params)
                .map(json -> json.has("~ret") && json.get("~ret").asInt() == 1);
    }

    /**
     * Get user by ID.
     */
    public Either<String, Map<String, Object>> getUserById(String userId, String tenantId) {
        Map<String, Object> params = Map.of("p_user_id", userId);

        return ordsClient.callProcedure(PKG_NAME, "GET_USER_BY_ID", params)
                .map(json -> {
                    Map<String, Object> user = new HashMap<>();
                    if (json.has("user_id")) user.put("userId", json.get("user_id").asText());
                    if (json.has("email")) user.put("email", json.get("email").asText());
                    if (json.has("username")) user.put("username", json.get("username").asText());
                    if (json.has("first_name")) user.put("firstName", json.get("first_name").asText());
                    if (json.has("last_name")) user.put("lastName", json.get("last_name").asText());
                    if (json.has("display_name")) user.put("displayName", json.get("display_name").asText());
                    if (json.has("status")) user.put("status", json.get("status").asText());
                    if (json.has("idp_source")) user.put("idpSource", json.get("idp_source").asText());
                    if (json.has("last_login_at")) user.put("lastLoginAt", json.get("last_login_at").asText());
                    user.put("tenantId", tenantId);
                    return user;
                });
    }

    /**
     * Update user profile.
     */
    public Either<String, Boolean> updateUserProfile(
            String userId,
            String firstName,
            String lastName,
            String displayName,
            String avatarUrl,
            String timezone,
            String locale
    ) {
        Map<String, Object> params = new HashMap<>();
        params.put("p_user_id", userId);
        if (firstName != null) params.put("p_first_name", firstName);
        if (lastName != null) params.put("p_last_name", lastName);
        if (displayName != null) params.put("p_display_name", displayName);
        if (avatarUrl != null) params.put("p_avatar_url", avatarUrl);
        if (timezone != null) params.put("p_timezone", timezone);
        if (locale != null) params.put("p_locale", locale);

        return ordsClient.callProcedure(PKG_NAME, "UPDATE_USER_PROFILE", params)
                .map(json -> true);
    }

    /**
     * Lock a user account.
     */
    public Either<String, Boolean> lockUser(String userId, String reason, String lockedBy) {
        Map<String, Object> params = Map.of(
            "p_user_id", userId,
            "p_reason", reason,
            "p_locked_by", lockedBy != null ? lockedBy : "SYSTEM"
        );

        return ordsClient.callProcedure(PKG_NAME, "LOCK_USER", params)
                .map(json -> true);
    }

    /**
     * Unlock a user account.
     */
    public Either<String, Boolean> unlockUser(String userId, String unlockedBy) {
        Map<String, Object> params = Map.of(
            "p_user_id", userId,
            "p_unlocked_by", unlockedBy != null ? unlockedBy : "SYSTEM"
        );

        return ordsClient.callProcedure(PKG_NAME, "UNLOCK_USER", params)
                .map(json -> true);
    }

    /**
     * Log a security event.
     */
    public void logSecurityEvent(String userId, String tenantId, String eventType, 
                                  String eventDetails, String ipAddress, 
                                  String userAgent, String sessionId) {
        Map<String, Object> params = new HashMap<>();
        params.put("p_user_id", userId);
        params.put("p_event_type", eventType);
        if (tenantId != null) params.put("p_tenant_id", tenantId);
        if (eventDetails != null) params.put("p_event_details", eventDetails);
        if (ipAddress != null) params.put("p_ip_address", ipAddress);
        if (userAgent != null) params.put("p_user_agent", userAgent);
        if (sessionId != null) params.put("p_session_id", sessionId);

        ordsClient.callProcedure(PKG_NAME, "LOG_SECURITY_EVENT", params)
                .peekLeft(error -> log.warn("Failed to log security event for user {}: {}", userId, error));
    }

    /**
     * Mask email for logging (PII protection).
     */
    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "***";
        }
        int atIndex = email.indexOf('@');
        String local = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        if (local.length() <= 2) {
            return "***" + domain;
        }
        return local.charAt(0) + "***" + local.charAt(local.length() - 1) + domain;
    }
}
