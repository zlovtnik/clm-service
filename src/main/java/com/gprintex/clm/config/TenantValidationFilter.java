package com.gprintex.clm.config;

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
 * Filter to validate tenant isolation.
 * 
 * Ensures:
 * 1. X-Tenant-Id header is present for protected endpoints
 * 2. Tenant from JWT matches X-Tenant-Id header (if configured)
 * 3. Sets tenant context for downstream processing
 * 
 * This filter runs after Spring Security authentication (Order 100).
 */
@Component
@Order(100)
public class TenantValidationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantValidationFilter.class);

    private final SecurityProperties securityProperties;

    public TenantValidationFilter(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, 
                                     FilterChain filterChain) throws ServletException, IOException {
        
        String path = request.getRequestURI();
        
        // Skip tenant validation for public/actuator endpoints
        if (isPublicEndpoint(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String tenantIdHeader = request.getHeader("X-Tenant-Id");
        
        // Check if tenant header is required
        if (securityProperties.getApi().isRequireTenantHeader() && 
            (tenantIdHeader == null || tenantIdHeader.isBlank())) {
            
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"X-Tenant-Id header is required\"}");
            return;
        }

        // Validate tenant from JWT if enabled
        if (securityProperties.getApi().isValidateTenantFromJwt() && tenantIdHeader != null) {
            String jwtTenant = extractTenantFromJwt();
            
            if (jwtTenant == null) {
                // JWT is present but tenant claim is missing
                log.warn("JWT tenant claim '{}' not found in token for request to {}", 
                    securityProperties.getJwt().getTenantClaim(), path);
                // Optionally reject - depending on policy, uncomment below:
                // response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                // response.setContentType("application/json");
                // response.getWriter().write("{\"error\": \"JWT missing tenant claim\"}");
                // return;
            } else if (!jwtTenant.equals(tenantIdHeader)) {
                log.warn("Tenant mismatch: JWT tenant '{}' does not match header '{}'", 
                    jwtTenant, tenantIdHeader);
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\": \"Tenant mismatch: JWT tenant does not match X-Tenant-Id header\"}");
                return;
            }
        }

        // Set tenant in request attribute for downstream use
        if (tenantIdHeader != null) {
            request.setAttribute("tenantId", tenantIdHeader);
        }

        filterChain.doFilter(request, response);
    }

    private boolean isPublicEndpoint(String path) {
        return path.startsWith("/actuator") ||
               path.startsWith("/api/v1/public") ||
               path.equals("/health") ||
               path.equals("/info");
    }

    private String extractTenantFromJwt() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            String tenantClaim = securityProperties.getJwt().getTenantClaim();
            return jwt.getClaimAsString(tenantClaim);
        }
        
        return null;
    }
}
