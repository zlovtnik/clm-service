package com.gprintex.clm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gprintex.clm.config.OrdsProperties;
import io.vavr.control.Either;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * Client for calling Oracle REST Data Services (ORDS) REST APIs.
 * Provides a type-safe wrapper around ORDS package procedure calls.
 */
@Component
public class OrdsClient {

    private static final Logger log = LoggerFactory.getLogger(OrdsClient.class);
    private static final int MAX_ERROR_BODY_LENGTH = 1000;

    private final WebClient ordsWebClient;
    private final OrdsProperties ordsProperties;
    private final ObjectMapper objectMapper;

    public OrdsClient(WebClient ordsWebClient, OrdsProperties ordsProperties, ObjectMapper objectMapper) {
        this.ordsWebClient = ordsWebClient;
        this.ordsProperties = ordsProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * Call an ORDS procedure with parameters and return the result.
     *
     * @param packageName   The PL/SQL package name (e.g., "contract_pkg")
     * @param procedureName The procedure/function name (e.g., "INSERT_CONTRACT")
     * @param params        Map of parameter names to values
     * @return JsonNode containing the response
     * @throws IllegalArgumentException if packageName or procedureName is null
     */
    public Mono<JsonNode> callProcedureAsync(String packageName, String procedureName, Map<String, Object> params) {
        if (packageName == null || packageName.isBlank()) {
            return Mono.error(new IllegalArgumentException("packageName must not be null or blank"));
        }
        if (procedureName == null || procedureName.isBlank()) {
            return Mono.error(new IllegalArgumentException("procedureName must not be null or blank"));
        }
        
        String url = "/" + packageName.toLowerCase(Locale.ROOT) + "/" + procedureName.toUpperCase(Locale.ROOT);
        
        return ordsWebClient.post()
            .uri(url)
            .bodyValue(params)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(Duration.ofMillis(ordsProperties.getReadTimeout()))
            .onErrorResume(TimeoutException.class, e -> {
                log.error("ORDS call timed out after {}ms: {}.{}", 
                    ordsProperties.getReadTimeout(), packageName, procedureName);
                return Mono.error(new OrdsException(
                    "ORDS call timed out: " + packageName + "." + procedureName,
                    504,
                    "Read timeout after " + ordsProperties.getReadTimeout() + "ms"
                ));
            })
            .onErrorResume(WebClientResponseException.class, e -> {
                String responseBody = truncateResponseBody(e.getResponseBodyAsString());
                log.error("ORDS call failed: {}.{} - status={}", 
                    packageName, procedureName, e.getStatusCode().value());
                return Mono.error(new OrdsException(
                    "ORDS call failed: " + packageName + "." + procedureName,
                    e.getStatusCode().value(),
                    responseBody
                ));
            });
    }

    private String truncateResponseBody(String body) {
        if (body == null) {
            return "";
        }
        if (body.length() <= MAX_ERROR_BODY_LENGTH) {
            return body;
        }
        return body.substring(0, MAX_ERROR_BODY_LENGTH) + "... [truncated]";
    }

    /**
     * Call an ORDS procedure synchronously (blocking).
     */
    public JsonNode callProcedureSync(String packageName, String procedureName, Map<String, Object> params) {
        return callProcedureAsync(packageName, procedureName, params).block();
    }

    /**
     * Call an ORDS procedure and return Either (for Vavr integration).
     */
    public Either<String, JsonNode> callProcedure(String packageName, String procedureName, Map<String, Object> params) {
        try {
            JsonNode result = callProcedureSync(packageName, procedureName, params);
            return Either.right(result);
        } catch (OrdsException e) {
            return Either.left(e.getMessage());
        } catch (Exception e) {
            return Either.left("ORDS call failed: " + e.getMessage());
        }
    }

    /**
     * Call an ORDS procedure that returns a list.
     */
    public Either<String, List<JsonNode>> callProcedureForList(String packageName, String procedureName, 
                                                                Map<String, Object> params, String listKey) {
        return callProcedure(packageName, procedureName, params)
            .map(result -> {
                List<JsonNode> list = new ArrayList<>();
                JsonNode data = result.has(listKey) ? result.get(listKey) : 
                               (result.has("~ret") ? result.get("~ret") : result);
                if (data != null && data.isArray()) {
                    data.forEach(list::add);
                }
                return list;
            });
    }

    /**
     * Call an ORDS function that returns a single value.
     */
    public <T> Mono<T> callFunction(String packageName, String functionName, Map<String, Object> params, Class<T> returnType) {
        return callProcedureAsync(packageName, functionName, params)
            .flatMap(json -> {
                // ORDS returns function results in "~ret" or the first property
                if (json.has("~ret")) {
                    return Mono.just(objectMapper.convertValue(json.get("~ret"), returnType));
                }
                var fields = json.fields();
                if (fields.hasNext()) {
                    return Mono.just(objectMapper.convertValue(fields.next().getValue(), returnType));
                }
                return Mono.empty();
            });
    }

    /**
     * Call an ORDS function synchronously.
     */
    public <T> T callFunctionSync(String packageName, String functionName, Map<String, Object> params, Class<T> returnType) {
        return callFunction(packageName, functionName, params, returnType).block();
    }

    /**
     * Extract a specific output parameter from ORDS response.
     */
    public <T> Optional<T> extractOutput(JsonNode response, String paramName, Class<T> type) {
        if (response == null) {
            return Optional.empty();
        }
        if (paramName == null || paramName.isBlank()) {
            throw new IllegalArgumentException("paramName must not be null or blank");
        }
        if (!response.has(paramName)) {
            return Optional.empty();
        }
        return Optional.of(objectMapper.convertValue(response.get(paramName), type));
    }

    /**
     * Exception for ORDS-specific errors.
     */
    public static class OrdsException extends RuntimeException {
        private final int statusCode;
        private final String responseBody;

        public OrdsException(String message, int statusCode, String responseBody) {
            super(message + " [status=" + statusCode + "]");
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getResponseBody() {
            return responseBody;
        }
    }
}
