package com.gprintex.clm.handler;

import com.gprintex.clm.domain.Customer;
import com.gprintex.clm.service.CustomerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Event handler for customer-related events.
 */
@Component("customerEventHandler")
public class CustomerEventHandler {

    private static final Logger log = LoggerFactory.getLogger(CustomerEventHandler.class);

    private final CustomerService customerService;
    private final ObjectMapper objectMapper;

    public CustomerEventHandler(CustomerService customerService, ObjectMapper objectMapper) {
        this.customerService = customerService;
        this.objectMapper = objectMapper;
    }

    @Handler
    public void onCreated(Exchange exchange) {
        var payload = exchange.getIn().getBody(String.class);
        
        try {
            @SuppressWarnings("unchecked")
            var data = objectMapper.readValue(payload, Map.class);
            
            // Validate required fields
            Object tenantIdObj = data.get("tenantId");
            Object customerIdObj = data.get("customerId");
            
            if (!(tenantIdObj instanceof String) || tenantIdObj == null) {
                log.error("Invalid or missing tenantId in customer created event");
                return;
            }
            if (!(customerIdObj instanceof Number)) {
                log.error("Invalid or missing customerId in customer created event");
                return;
            }
            
            var tenantId = (String) tenantIdObj;
            var customerId = ((Number) customerIdObj).longValue();
            
            customerService.findById(tenantId, customerId)
                .ifPresent(customer -> {
                    logEvent("CUSTOMER_CREATED", customer);
                });
        } catch (ClassCastException e) {
            log.error("Type error parsing customer created payload: {}", e.getMessage());
        } catch (Exception e) {
            exchange.setException(e);
        }
    }

    @Handler
    public void onUpdated(Exchange exchange) {
        var payload = exchange.getIn().getBody(String.class);
        
        try {
            @SuppressWarnings("unchecked")
            var data = objectMapper.readValue(payload, Map.class);
            
            Object tenantIdObj = data.get("tenantId");
            Object customerIdObj = data.get("customerId");
            
            if (!(tenantIdObj instanceof String) || tenantIdObj == null) {
                log.error("Invalid or missing tenantId in customer updated event");
                return;
            }
            if (!(customerIdObj instanceof Number)) {
                log.error("Invalid or missing customerId in customer updated event");
                return;
            }
            
            var tenantId = (String) tenantIdObj;
            var customerId = ((Number) customerIdObj).longValue();
            
            customerService.findById(tenantId, customerId)
                .ifPresent(customer -> {
                    logEvent("CUSTOMER_UPDATED", customer);
                });
        } catch (ClassCastException e) {
            log.error("Type error parsing customer updated payload: {}", e.getMessage());
        } catch (Exception e) {
            exchange.setException(e);
        }
    }

    private void logEvent(String eventType, Customer customer) {
        log.info("[{}] Customer: {} (ID: {}, Code: {})",
            eventType,
            customer.name(),
            customer.id().orElse(-1L),
            customer.customerCode()
        );
    }
}
