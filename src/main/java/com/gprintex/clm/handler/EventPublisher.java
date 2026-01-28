package com.gprintex.clm.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.Handler;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Event publisher for Camel routes.
 * Converts domain events to integration messages.
 */
@Component("eventPublisher")
public class EventPublisher {

    private final ObjectMapper objectMapper;

    public EventPublisher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Handler
    public void publish(Exchange exchange) {
        var messageType = exchange.getIn().getHeader("messageType", String.class);
        var body = exchange.getIn().getBody();
        
        try {
            String payload;
            if (body instanceof String) {
                payload = (String) body;
            } else {
                payload = objectMapper.writeValueAsString(body);
            }
            
            // Set message headers for integration
            var messageId = UUID.randomUUID().toString();
            var timestamp = System.currentTimeMillis();
            exchange.getIn().setHeader("messageId", messageId);
            exchange.getIn().setHeader("timestamp", timestamp);
            
            // Build body with null-safe values using mutable map
            var bodyMap = new java.util.HashMap<String, Object>();
            bodyMap.put("messageId", messageId);
            bodyMap.put("messageType", messageType != null ? messageType : "UNKNOWN");
            bodyMap.put("timestamp", timestamp);
            bodyMap.put("payload", payload);
            exchange.getIn().setBody(bodyMap);
            
        } catch (Exception e) {
            exchange.setException(e);
        }
    }
}
