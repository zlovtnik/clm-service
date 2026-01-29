package com.gprintex.clm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Security configuration properties.
 */
@Component
@ConfigurationProperties(prefix = "security")
public class SecurityProperties {

    private Jwt jwt = new Jwt();
    private Cors cors = new Cors();
    private Api api = new Api();

    public Jwt getJwt() {
        return jwt;
    }

    public void setJwt(Jwt jwt) {
        this.jwt = jwt;
    }

    public Cors getCors() {
        return cors;
    }

    public void setCors(Cors cors) {
        this.cors = cors;
    }

    public Api getApi() {
        return api;
    }

    public void setApi(Api api) {
        this.api = api;
    }

    /**
     * JWT/OAuth2 configuration.
     */
    public static class Jwt {
        /**
         * JWK Set URI for validating JWT signatures.
         * Example: https://your-idp.com/.well-known/jwks.json
         */
        private String jwkSetUri;

        /**
         * Expected issuer claim (iss) in JWT.
         */
        private String issuer;

        /**
         * Expected audience claim (aud) in JWT.
         */
        private String audience;

        /**
         * Claim name containing roles/authorities.
         * Common values: "roles", "scope", "authorities", "realm_access.roles"
         */
        private String rolesClaim = "roles";

        /**
         * Claim name containing tenant ID.
         */
        private String tenantClaim = "tenant_id";

        /**
         * Claim name containing user ID.
         */
        private String userClaim = "sub";

        public String getJwkSetUri() {
            return jwkSetUri;
        }

        public void setJwkSetUri(String jwkSetUri) {
            this.jwkSetUri = jwkSetUri;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        public String getRolesClaim() {
            return rolesClaim;
        }

        public void setRolesClaim(String rolesClaim) {
            this.rolesClaim = rolesClaim;
        }

        public String getTenantClaim() {
            return tenantClaim;
        }

        public void setTenantClaim(String tenantClaim) {
            this.tenantClaim = tenantClaim;
        }

        public String getUserClaim() {
            return userClaim;
        }

        public void setUserClaim(String userClaim) {
            this.userClaim = userClaim;
        }
    }

    /**
     * CORS configuration.
     */
    public static class Cors {
        /**
         * Allowed origins for CORS.
         * Use "*" for development, specific origins for production.
         */
        private List<String> allowedOrigins = List.of("http://localhost:3000", "http://localhost:5173");

        /**
         * Whether to allow credentials in CORS requests.
         */
        private boolean allowCredentials = true;

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }

        public boolean isAllowCredentials() {
            return allowCredentials;
        }

        public void setAllowCredentials(boolean allowCredentials) {
            this.allowCredentials = allowCredentials;
        }
    }

    /**
     * API security configuration.
     */
    public static class Api {
        /**
         * Whether to require X-Tenant-Id header for all requests.
         */
        private boolean requireTenantHeader = true;

        /**
         * Whether to validate tenant claim from JWT matches X-Tenant-Id header.
         */
        private boolean validateTenantFromJwt = true;

        /**
         * API key for service-to-service authentication (optional).
         */
        private String apiKey;

        /**
         * Whether to automatically sync users from JWT to Oracle on each request.
         * When enabled, user info from JWT claims is synced to user_accounts table.
         */
        private boolean autoSyncUsers = true;

        public boolean isRequireTenantHeader() {
            return requireTenantHeader;
        }

        public void setRequireTenantHeader(boolean requireTenantHeader) {
            this.requireTenantHeader = requireTenantHeader;
        }

        public boolean isValidateTenantFromJwt() {
            return validateTenantFromJwt;
        }

        public void setValidateTenantFromJwt(boolean validateTenantFromJwt) {
            this.validateTenantFromJwt = validateTenantFromJwt;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public boolean isAutoSyncUsers() {
            return autoSyncUsers;
        }

        public void setAutoSyncUsers(boolean autoSyncUsers) {
            this.autoSyncUsers = autoSyncUsers;
        }
    }
}
