package com.gprintex.clm.api;

import com.gprintex.clm.service.UserService;
import io.vavr.control.Either;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for user management.
 * 
 * <p>Provides endpoints for:
 * <ul>
 *   <li>Getting current user info</li>
 *   <li>Updating user profile</li>
 *   <li>Managing tenant access</li>
 *   <li>Administrative user operations</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Get the current authenticated user's info.
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        String userId = jwt.getSubject();
        log.debug("Getting current user info for userId={}", userId);

        return userService.getUserById(userId, tenantId)
            .fold(
                error -> {
                    log.warn("Failed to get user {}: {}", userId, error);
                    return ResponseEntity.notFound().build();
                },
                user -> ResponseEntity.ok(user)
            );
    }

    /**
     * Update the current user's profile.
     */
    @PutMapping("/me/profile")
    public ResponseEntity<Map<String, Object>> updateProfile(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestBody ProfileUpdateRequest request) {
        
        String userId = jwt.getSubject();
        log.info("Updating profile for userId={}", userId);

        return userService.updateUserProfile(
                userId,
                request.firstName(),
                request.lastName(),
                request.displayName(),
                request.avatarUrl(),
                request.timezone(),
                request.locale()
            )
            .flatMap(ignored -> userService.getUserById(userId, tenantId))
            .fold(
                error -> {
                    log.error("Failed to update profile for {}: {}", userId, error);
                    return ResponseEntity.badRequest().body(Map.of("error", error));
                },
                user -> ResponseEntity.ok(user)
            );
    }

    /**
     * Get a user by ID (admin only).
     */
    @GetMapping("/{userId}")
    public ResponseEntity<Map<String, Object>> getUserById(
            @PathVariable String userId,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        log.debug("Admin getting user info for userId={}", userId);

        return userService.getUserById(userId, tenantId)
            .fold(
                error -> ResponseEntity.notFound().build(),
                user -> ResponseEntity.ok(user)
            );
    }

    /**
     * Check if a user has access to a tenant.
     */
    @GetMapping("/{userId}/tenants/{targetTenantId}/access")
    public ResponseEntity<Map<String, Object>> checkTenantAccess(
            @PathVariable String userId,
            @PathVariable String targetTenantId,
            @RequestParam(required = false) String requiredRole) {
        
        String role = requiredRole != null ? requiredRole : "VIEWER";
        
        return userService.hasTenantAccess(userId, targetTenantId, role)
            .fold(
                error -> ResponseEntity.ok(Map.of(
                    "hasAccess", false,
                    "error", error
                )),
                hasAccess -> ResponseEntity.ok(Map.of(
                    "hasAccess", hasAccess,
                    "userId", userId,
                    "tenantId", targetTenantId,
                    "requiredRole", role
                ))
            );
    }

    /**
     * Grant tenant access to a user (admin only).
     */
    @PostMapping("/{userId}/tenants/{targetTenantId}/access")
    public ResponseEntity<Map<String, Object>> grantTenantAccess(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String userId,
            @PathVariable String targetTenantId,
            @RequestBody TenantAccessRequest request) {
        
        String grantedBy = jwt.getSubject();
        log.info("User {} granting {} access to tenant {} for user {}", 
                grantedBy, request.role(), targetTenantId, userId);

        return userService.grantTenantAccess(userId, targetTenantId, request.role(), grantedBy)
            .fold(
                error -> {
                    log.error("Failed to grant access: {}", error);
                    return ResponseEntity.badRequest().body(Map.of("error", error));
                },
                success -> ResponseEntity.ok(Map.of(
                    "success", success,
                    "userId", userId,
                    "tenantId", targetTenantId,
                    "role", request.role()
                ))
            );
    }

    /**
     * Revoke tenant access from a user (admin only).
     */
    @DeleteMapping("/{userId}/tenants/{targetTenantId}/access")
    public ResponseEntity<Map<String, Object>> revokeTenantAccess(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String userId,
            @PathVariable String targetTenantId) {
        
        String revokedBy = jwt.getSubject();
        log.info("User {} revoking tenant {} access for user {}", 
                revokedBy, targetTenantId, userId);

        return userService.revokeTenantAccess(userId, targetTenantId, revokedBy)
            .fold(
                error -> {
                    log.error("Failed to revoke access: {}", error);
                    return ResponseEntity.badRequest().body(Map.of("error", error));
                },
                success -> ResponseEntity.ok(Map.of(
                    "success", success,
                    "userId", userId,
                    "tenantId", targetTenantId
                ))
            );
    }

    /**
     * Lock a user account (admin only).
     */
    @PostMapping("/{userId}/lock")
    public ResponseEntity<Map<String, Object>> lockUser(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String userId,
            @RequestBody LockUserRequest request) {
        
        String lockedBy = jwt.getSubject();
        log.warn("User {} locking account {}: {}", lockedBy, userId, request.reason());

        return userService.lockUser(userId, request.reason(), lockedBy)
            .fold(
                error -> ResponseEntity.badRequest().body(Map.of("error", error)),
                success -> ResponseEntity.ok(Map.of(
                    "success", success,
                    "userId", userId,
                    "status", "LOCKED"
                ))
            );
    }

    /**
     * Unlock a user account (admin only).
     */
    @PostMapping("/{userId}/unlock")
    public ResponseEntity<Map<String, Object>> unlockUser(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String userId) {
        
        String unlockedBy = jwt.getSubject();
        log.info("User {} unlocking account {}", unlockedBy, userId);

        return userService.unlockUser(userId, unlockedBy)
            .fold(
                error -> ResponseEntity.badRequest().body(Map.of("error", error)),
                success -> ResponseEntity.ok(Map.of(
                    "success", success,
                    "userId", userId,
                    "status", "ACTIVE"
                ))
            );
    }

    /**
     * Sync current user from JWT (force re-sync).
     */
    @PostMapping("/me/sync")
    public ResponseEntity<Map<String, Object>> syncCurrentUser(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestParam(defaultValue = "KEYCLOAK") String idpSource) {
        
        log.info("Force syncing user from JWT, idpSource={}", idpSource);

        return userService.syncUserFromJwt(jwt, tenantId, idpSource)
            .flatMap(userId -> userService.getUserById(userId, tenantId))
            .fold(
                error -> {
                    log.error("Failed to sync user: {}", error);
                    return ResponseEntity.badRequest().body(Map.of("error", error));
                },
                user -> ResponseEntity.ok(user)
            );
    }

    // Request DTOs as records
    
    public record ProfileUpdateRequest(
        String firstName,
        String lastName,
        String displayName,
        String avatarUrl,
        String timezone,
        String locale
    ) {}

    public record TenantAccessRequest(
        String role
    ) {
        public TenantAccessRequest {
            if (role == null || role.isBlank()) {
                role = "VIEWER";
            }
        }
    }

    public record LockUserRequest(
        String reason
    ) {
        public LockUserRequest {
            if (reason == null || reason.isBlank()) {
                reason = "Locked by administrator";
            }
        }
    }
}
