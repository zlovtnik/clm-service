package com.gprintex.clm.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Configuration for Oracle REST Data Services (ORDS) client.
 * Handles authentication and HTTP client setup for calling ORDS REST APIs.
 */
@Configuration
@EnableConfigurationProperties(OrdsProperties.class)
public class OrdsClientConfig {

    private static final Logger log = LoggerFactory.getLogger(OrdsClientConfig.class);

    private final OrdsProperties ordsProperties;

    public OrdsClientConfig(OrdsProperties ordsProperties) {
        this.ordsProperties = ordsProperties;
    }

    /**
     * WebClient configured for ORDS API calls with authentication.
     */
    @Bean
    public WebClient ordsWebClient(WebClient.Builder builder) {
        return builder
            .baseUrl(ordsProperties.getBaseUrl())
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .filter(authenticationFilter())
            .filter(loggingFilter())
            .build();
    }

    /**
     * Authentication filter - adds Basic Auth or OAuth2 token based on configuration.
     */
    private ExchangeFilterFunction authenticationFilter() {
        return ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
            var mutatedRequest = org.springframework.web.reactive.function.client.ClientRequest.from(clientRequest);
            
            OrdsProperties.AuthType authType = ordsProperties.getAuthType();
            if (authType == null) {
                log.warn("ORDS authType is null, defaulting to NONE");
                return Mono.just(mutatedRequest.build());
            }
            
            switch (authType) {
                case BASIC -> {
                    String username = ordsProperties.getUsername();
                    String password = ordsProperties.getPassword();
                    if (username == null || username.isBlank() || password == null || password.isBlank()) {
                        log.warn("ORDS BASIC auth configured but credentials missing, skipping auth header");
                        return Mono.just(mutatedRequest.build());
                    }
                    String credentials = username + ":" + password;
                    String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                    mutatedRequest.header(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
                    return Mono.just(mutatedRequest.build());
                }
                case OAUTH2 -> {
                    // OAuth2 token would be fetched from token endpoint
                    // For now, assume token is pre-configured or fetched separately
                    if (ordsProperties.getAccessToken() != null) {
                        mutatedRequest.header(HttpHeaders.AUTHORIZATION, "Bearer " + ordsProperties.getAccessToken());
                    }
                    return Mono.just(mutatedRequest.build());
                }
                case JWT_PASSTHROUGH -> {
                    // Forward the Keycloak JWT from the incoming request to ORDS
                    return ReactiveSecurityContextHolder.getContext()
                        .map(ctx -> ctx.getAuthentication())
                        .filter(auth -> auth instanceof JwtAuthenticationToken)
                        .cast(JwtAuthenticationToken.class)
                        .map(jwtAuth -> {
                            String token = jwtAuth.getToken().getTokenValue();
                            mutatedRequest.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
                            return mutatedRequest.build();
                        })
                        .switchIfEmpty(Mono.defer(() -> {
                            // Fallback to Basic Auth if no JWT present (e.g., internal calls)
                            if (ordsProperties.getUsername() != null && ordsProperties.getPassword() != null) {
                                String credentials = ordsProperties.getUsername() + ":" + ordsProperties.getPassword();
                                String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                                mutatedRequest.header(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
                            }
                            return Mono.just(mutatedRequest.build());
                        }));
                }
                case NONE -> {
                    return Mono.just(mutatedRequest.build());
                }
                default -> {
                    log.warn("Unknown ORDS authType: {}", authType);
                    return Mono.just(mutatedRequest.build());
                }
            }
        });
    }

    /**
     * Logging filter for debugging ORDS calls.
     */
    private ExchangeFilterFunction loggingFilter() {
        return ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
            if (ordsProperties.isDebugEnabled()) {
                log.debug("ORDS Request: {} {}", clientRequest.method(), clientRequest.url());
            }
            return Mono.just(clientRequest);
        });
    }
}
