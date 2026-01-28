package com.gprintex.clm.handler;

import com.gprintex.clm.domain.Contract;
import com.gprintex.clm.service.ContractService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Event handler for contract-related events.
 * Used by Camel routes to process contract events.
 */
@Component("contractEventHandler")
public class ContractEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ContractEventHandler.class);

    private final ContractService contractService;
    private final ObjectMapper objectMapper;

    public ContractEventHandler(ContractService contractService, ObjectMapper objectMapper) {
        this.contractService = contractService;
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
            Object contractIdObj = data.get("contractId");
            
            if (!(tenantIdObj instanceof String) || tenantIdObj == null) {
                log.error("Invalid or missing tenantId in contract created event");
                return;
            }
            if (!(contractIdObj instanceof Number)) {
                log.error("Invalid or missing contractId in contract created event");
                return;
            }
            
            var tenantId = (String) tenantIdObj;
            var contractId = ((Number) contractIdObj).longValue();
            
            contractService.findById(tenantId, contractId)
                .ifPresent(contract -> {
                    // Perform post-creation actions
                    logEvent("CONTRACT_CREATED", contract);
                });
        } catch (ClassCastException e) {
            log.error("Type error parsing contract created payload: {}", e.getMessage());
            exchange.setException(e);  // Propagate to route error handling
        } catch (Exception e) {
            exchange.setException(e);
        }
    }

    @Handler
    public void onUpdated(Exchange exchange) {
        var payload = exchange.getIn().getBody(String.class);
        
        try {
            var data = objectMapper.readValue(payload, Map.class);
            var tenantId = (String) data.get("tenantId");
            var contractId = ((Number) data.get("contractId")).longValue();
            
            contractService.findById(tenantId, contractId)
                .ifPresent(contract -> {
                    logEvent("CONTRACT_UPDATED", contract);
                });
        } catch (Exception e) {
            exchange.setException(e);
        }
    }

    @Handler
    public void onStatusChanged(Exchange exchange) {
        var payload = exchange.getIn().getBody(String.class);
        
        try {
            @SuppressWarnings("unchecked")
            var data = objectMapper.readValue(payload, Map.class);
            
            Object tenantIdObj = data.get("tenantId");
            Object contractIdObj = data.get("contractId");
            
            if (!(tenantIdObj instanceof String) || tenantIdObj == null) {
                log.error("Invalid or missing tenantId in status changed event");
                return;
            }
            if (!(contractIdObj instanceof Number)) {
                log.error("Invalid or missing contractId in status changed event");
                return;
            }
            
            var tenantId = (String) tenantIdObj;
            var contractId = ((Number) contractIdObj).longValue();
            var oldStatus = (String) data.get("oldStatus");
            var newStatus = (String) data.get("newStatus");
            
            contractService.findById(tenantId, contractId)
                .ifPresent(contract -> {
                    log.info("Contract status transition: {} -> {} for contract {}", oldStatus, newStatus, contract.contractNumber());
                    logEvent("CONTRACT_STATUS_CHANGED", contract);
                    
                    // Handle specific status transitions
                    switch (newStatus) {
                        case "ACTIVE" -> handleContractActivation(contract);
                        case "CANCELLED" -> handleContractCancellation(contract);
                        case "COMPLETED" -> handleContractCompletion(contract);
                    }
                });
        } catch (Exception e) {
            exchange.setException(e);
        }
    }

    private void handleContractActivation(Contract contract) {
        // Trigger billing setup, notifications, etc.
    }

    private void handleContractCancellation(Contract contract) {
        // Trigger cleanup, notifications, etc.
    }

    private void handleContractCompletion(Contract contract) {
        // Trigger archival, renewal reminders, etc.
    }

    private void logEvent(String eventType, Contract contract) {
        // Log event for audit trail using SLF4J
        log.info("[{}] Contract: {} (ID: {}, Status: {})",
            eventType,
            contract.contractNumber(),
            contract.id().orElse(-1L),
            contract.status()
        );
    }
}
