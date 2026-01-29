package com.gprintex.clm.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;

/**
 * Configuration properties for Oracle REST Data Services (ORDS) connection.
 */
@ConfigurationProperties(prefix = "ords")
public class OrdsProperties {

    private static final Logger log = LoggerFactory.getLogger(OrdsProperties.class);

    /**
     * Base URL for ORDS REST API.
     * Example: https://xxxxx.adb.us-chicago-1.oraclecloudapps.com/ords/rcs
     */
    private String baseUrl;

    /**
     * Schema/workspace name in ORDS.
     */
    private String schema = "rcs";

    /**
     * Authentication type: NONE, BASIC, OAUTH2, JWT_PASSTHROUGH
     */
    private AuthType authType = AuthType.BASIC;

    /**
     * Username for Basic Auth.
     */
    private String username;

    /**
     * Password for Basic Auth.
     */
    private String password;

    /**
     * OAuth2 client ID.
     */
    private String clientId;

    /**
     * OAuth2 client secret.
     */
    private String clientSecret;

    /**
     * OAuth2 token endpoint URL.
     */
    private String tokenUrl;

    /**
     * Pre-configured access token (for testing or static tokens).
     */
    private String accessToken;

    /**
     * Connection timeout in milliseconds.
     */
    private int connectTimeout = 10000;

    /**
     * Read timeout in milliseconds.
     */
    private int readTimeout = 30000;

    /**
     * Enable debug logging for ORDS calls.
     */
    private boolean debugEnabled = false;

    public enum AuthType {
        /** No authentication */
        NONE,
        /** HTTP Basic Auth with username/password */
        BASIC,
        /** OAuth2 client credentials flow */
        OAUTH2,
        /** Pass through JWT from incoming request (Keycloak → ORDS) */
        JWT_PASSTHROUGH
    }

    // Getters and Setters

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public AuthType getAuthType() {
        return authType;
    }

    public void setAuthType(AuthType authType) {
        this.authType = authType;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getTokenUrl() {
        return tokenUrl;
    }

    public void setTokenUrl(String tokenUrl) {
        this.tokenUrl = tokenUrl;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public int getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }

    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    public void setDebugEnabled(boolean debugEnabled) {
        this.debugEnabled = debugEnabled;
    }

    /**
     * Validate ORDS configuration at startup.
     * Fails fast if required credentials are missing for the selected auth type.
     */
    @PostConstruct
    public void validate() {
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("ORDS baseUrl is not configured - ORDS calls will fail");
        }
        
        if (authType == null) {
            log.warn("ORDS authType is not configured, defaulting to NONE");
            return;
        }
        
        switch (authType) {
            case BASIC -> {
                if (isBlank(username) || isBlank(password)) {
                    throw new IllegalStateException(
                        "ORDS auth-type is BASIC but username/password are not configured. " +
                        "Set ORDS_USERNAME and ORDS_PASSWORD environment variables.");
                }
            }
            case OAUTH2 -> {
                if (isBlank(clientId) || isBlank(clientSecret) || isBlank(tokenUrl)) {
                    throw new IllegalStateException(
                        "ORDS auth-type is OAUTH2 but client credentials are not configured. " +
                        "Set ORDS_CLIENT_ID, ORDS_CLIENT_SECRET, and ORDS_TOKEN_URL environment variables.");
                }
            }
            case JWT_PASSTHROUGH -> {
                // JWT_PASSTHROUGH can optionally fall back to Basic Auth
                if (isBlank(username) || isBlank(password)) {
                    log.info("ORDS auth-type is JWT_PASSTHROUGH with no Basic Auth fallback configured");
                }
            }
            case NONE -> log.warn("ORDS auth-type is NONE - requests will not be authenticated");
        }
    }
    
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Build the full URL for a package endpoint.
     * @param packageName the package name (required)
     * @return the full URL for the package
     * @throws IllegalStateException if baseUrl is not configured
     * @throws IllegalArgumentException if packageName is null or blank
     */
    public String getPackageUrl(String packageName) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("ORDS baseUrl must be configured");
        }
        if (packageName == null || packageName.isBlank()) {
            throw new IllegalArgumentException("packageName must not be null or blank");
        }
        String normalizedBaseUrl = baseUrl.endsWith("/") 
            ? baseUrl.substring(0, baseUrl.length() - 1) 
            : baseUrl;
        return normalizedBaseUrl + "/" + packageName.toLowerCase(Locale.ROOT);
    }

    /**
     * Build the full URL for a specific procedure/function.
     * @param packageName the package name (required)
     * @param procedureName the procedure name (required)
     * @return the full URL for the procedure
     * @throws IllegalStateException if baseUrl is not configured
     * @throws IllegalArgumentException if packageName or procedureName is null or blank
     */
    public String getProcedureUrl(String packageName, String procedureName) {
        if (procedureName == null || procedureName.isBlank()) {
            throw new IllegalArgumentException("procedureName must not be null or blank");
        }
        return getPackageUrl(packageName) + "/" + procedureName.toUpperCase(Locale.ROOT);
    }
}
