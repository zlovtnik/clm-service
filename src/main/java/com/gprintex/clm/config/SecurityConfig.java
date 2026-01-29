package com.gprintex.clm.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Security configuration for CLM Service API.
 * 
 * Supports:
 * - JWT Bearer token authentication (OAuth2 Resource Server)
 * - CORS configuration for frontend access
 * - Role-based access control
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final SecurityProperties securityProperties;

    public SecurityConfig(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Bean
    @Profile("!test")
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF for stateless API
            .csrf(csrf -> csrf.disable())
            
            // Enable CORS
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            
            // Stateless session management
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            
            // Authorization rules
            .authorizeHttpRequests(auth -> auth
                // Public endpoints
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/api/v1/public/**").permitAll()
                
                // Admin endpoints require ADMIN role
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                
                // All other API endpoints require authentication
                .requestMatchers("/api/v1/**").authenticated()
                
                // Default: require authentication for any other request
                .anyRequest().authenticated()
            )
            
            // OAuth2 Resource Server with JWT
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .jwtAuthenticationConverter(jwtAuthenticationConverter())
                )
            );

        return http.build();
    }

    /**
     * Test profile security - more permissive for testing.
     */
    @Bean
    @Profile("test")
    public SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * CORS configuration for frontend access.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // Allowed origins from configuration - validate no wildcards with credentials
        List<String> allowedOrigins = securityProperties.getCors().getAllowedOrigins();
        boolean allowCredentials = securityProperties.getCors().isAllowCredentials();
        
        if (allowedOrigins != null && allowedOrigins.contains("*")) {
            if (allowCredentials) {
                throw new IllegalStateException(
                    "CORS misconfiguration: Cannot use wildcard origin '*' with allowCredentials=true. " +
                    "Either specify explicit origins or set security.cors.allow-credentials=false");
            }
            log.warn("SECURITY WARNING: CORS allows wildcard origin '*'. " +
                     "This should only be used in development environments.");
        }
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowCredentials(allowCredentials);
        
        // Allowed methods
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        
        // Allowed headers
        configuration.setAllowedHeaders(Arrays.asList(
            "Authorization",
            "Content-Type",
            "X-Tenant-Id",
            "X-User-Id",
            "X-Request-Id",
            "Accept",
            "Origin"
        ));
        
        // Exposed headers (for frontend to read)
        configuration.setExposedHeaders(Arrays.asList(
            "X-Request-Id",
            "X-Total-Count",
            "X-Page-Size",
            "X-Page-Number"
        ));
        
        // Allow credentials (cookies, authorization headers)
        configuration.setAllowCredentials(true);
        
        // Cache preflight requests for 1 hour
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    /**
     * JWT decoder for validating tokens.
     */
    @Bean
    @Profile("!test")
    public JwtDecoder jwtDecoder() {
        // Configure JWT validation based on issuer
        return NimbusJwtDecoder
            .withJwkSetUri(securityProperties.getJwt().getJwkSetUri())
            .build();
    }

    /**
     * Convert JWT claims to Spring Security authorities.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        
        // Extract roles from custom claim (adjust based on your IdP)
        grantedAuthoritiesConverter.setAuthoritiesClaimName(securityProperties.getJwt().getRolesClaim());
        grantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        
        return jwtAuthenticationConverter;
    }
}
