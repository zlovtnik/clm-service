package com.gprintex.clm.config;

import com.gprintex.clm.service.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter that synchronizes authenticated users from JWT to Oracle database.
 * 
 * <p>This filter runs after authentication and:
 * <ul>
 *   <li>Extracts user info from JWT claims</li>
 *   <li>Syncs user to Oracle user_accounts table via ORDS</li>
 *   <li>Records login events for audit</li>
 * </ul>
 * 
 * <p>Order is 110 to run after TenantValidationFilter (100).
 */
@Component
@Order(110)
public class UserSyncFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UserSyncFilter.class);

    private final UserService userService;
    private final SecurityProperties securityProperties;

    public UserSyncFilter(UserService userService, SecurityProperties securityProperties) {
        this.userService = userService;
        this.securityProperties = securityProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, 
                                     FilterChain filterChain) throws ServletException, IOException {
        
        // Skip sync for public endpoints
        if (isPublicEndpoint(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        // Check if user sync is enabled
        if (!securityProperties.getApi().isAutoSyncUsers()) {
            filterChain.doFilter(request, response);
            return;
        }

        // Get JWT from security context
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            String tenantId = getTenantId(request, jwt);
            String idpSource = detectIdpSource(jwt);
            
            // Sync user asynchronously (don't block the request)
            // In production, consider using async or caching
            try {
                userService.syncUserFromJwt(jwt, tenantId, idpSource)
                    .peek(userId -> {
                        // Store user ID in request for downstream use
                        request.setAttribute("userId", userId);
                        
                        // Record login event (only on first request of session)
                        if (request.getSession(false) == null) {
                            userService.recordLogin(
                                userId,
                                tenantId,
                                null, // No session ID yet
                                getClientIp(request),
                                request.getHeader("User-Agent")
                            );
                        }
                    })
                    .peekLeft(error -> log.warn("User sync failed: {}", error));
            } catch (Exception e) {
                // Don't fail the request if sync fails
                log.error("Error syncing user from JWT: {}", e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isPublicEndpoint(String path) {
        return path.startsWith("/actuator") ||
               path.startsWith("/api/v1/public") ||
               path.equals("/health") ||
               path.equals("/info");
    }

    private String getTenantId(HttpServletRequest request, Jwt jwt) {
        // First try header
        String headerTenant = request.getHeader("X-Tenant-Id");
        if (headerTenant != null && !headerTenant.isBlank()) {
            return headerTenant;
        }
        
        // Then try JWT claim
        String tenantClaim = securityProperties.getJwt().getTenantClaim();
        String jwtTenant = jwt.getClaimAsString(tenantClaim);
        if (jwtTenant != null && !jwtTenant.isBlank()) {
            return jwtTenant;
        }
        
        // Default tenant
        return "DEFAULT";
    }

    private String detectIdpSource(Jwt jwt) {
        String issuer = jwt.getIssuer() != null ? jwt.getIssuer().toString() : "";
        
        if (issuer.contains("keycloak")) {
            return "KEYCLOAK";
        } else if (issuer.contains("oracle") || issuer.contains("ords")) {
            return "ORACLE_ORDS";
        } else if (issuer.contains("login.microsoftonline") || issuer.contains("azure")) {
            return "AZURE_AD";
        } else if (issuer.contains("okta")) {
            return "OKTA";
        }
        
        return "KEYCLOAK"; // Default
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // First IP in the list is the client
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
