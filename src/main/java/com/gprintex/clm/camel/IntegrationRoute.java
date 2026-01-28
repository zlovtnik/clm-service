package com.gprintex.clm.camel;

import com.gprintex.clm.config.ClmProperties;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * Camel route for EIP message routing patterns.
 * Implements: Content-Based Router, Message Aggregator, Idempotent Consumer.
 */
@Component
public class IntegrationRoute extends RouteBuilder {

    private final ClmProperties properties;

    public IntegrationRoute(ClmProperties properties) {
        this.properties = properties;
    }

    @Override
    public void configure() throws Exception {

        // ====================================================================
        // MAIN MESSAGE ROUTER - Content-Based Routing
        // ====================================================================
        from("direct:route-message")
            .routeId("message-router")
            .log("Routing message: ${header.messageType} with key ${header.routingKey}")
            // Check for duplicates first (Idempotent Consumer pattern) - preserve body
            .setProperty("isDuplicate").method("integrationService", "isDuplicate(${header.messageId})")
            .choice()
                .when(exchangeProperty("isDuplicate").isEqualTo(true))
                    .log("Duplicate message detected: ${header.messageId}")
                    .stop()
            .end()
            // Content-based routing
            .choice()
                .when(header("messageType").isEqualTo("CONTRACT_CREATED"))
                    .to("direct:handle-contract-created")
                .when(header("messageType").isEqualTo("CONTRACT_UPDATED"))
                    .to("direct:handle-contract-updated")
                .when(header("messageType").isEqualTo("CONTRACT_STATUS_CHANGED"))
                    .to("direct:handle-contract-status-changed")
                .when(header("messageType").isEqualTo("CUSTOMER_CREATED"))
                    .to("direct:handle-customer-created")
                .when(header("messageType").isEqualTo("CUSTOMER_UPDATED"))
                    .to("direct:handle-customer-updated")
                .when(header("messageType").isEqualTo("ETL_BATCH"))
                    .to("direct:handle-etl-batch")
                .otherwise()
                    .log("Unknown message type: ${header.messageType}")
                    .to("seda:unknown-message-type")
            .end()
            // Mark as processed
            .bean("integrationService", "markProcessed(${header.messageId}, ${header.correlationId})");

        // ====================================================================
        // CONTRACT EVENT HANDLERS
        // ====================================================================
        from("direct:handle-contract-created")
            .routeId("handle-contract-created")
            .log("Processing contract created event")
            .bean("contractEventHandler", "onCreated");

        from("direct:handle-contract-updated")
            .routeId("handle-contract-updated")
            .log("Processing contract updated event")
            .bean("contractEventHandler", "onUpdated");

        from("direct:handle-contract-status-changed")
            .routeId("handle-contract-status-changed")
            .log("Processing contract status changed event")
            .bean("contractEventHandler", "onStatusChanged");

        // ====================================================================
        // CUSTOMER EVENT HANDLERS
        // ====================================================================
        from("direct:handle-customer-created")
            .routeId("handle-customer-created")
            .log("Processing customer created event")
            .bean("customerEventHandler", "onCreated");

        from("direct:handle-customer-updated")
            .routeId("handle-customer-updated")
            .log("Processing customer updated event")
            .bean("customerEventHandler", "onUpdated");

        // ====================================================================
        // ETL BATCH HANDLER - Splitter pattern
        // ====================================================================
        from("direct:handle-etl-batch")
            .routeId("handle-etl-batch")
            .log("Processing ETL batch with ${body.records != null ? body.records.size() : 0} records")
            .choice()
                .when(simple("${header.entityType} == 'CONTRACT'"))
                    .to("direct:contract-ingest")
                .when(simple("${header.entityType} == 'CUSTOMER'"))
                    .to("direct:customer-ingest")
                .otherwise()
                    .log("Unknown entity type: ${header.entityType}")
                    .to("seda:unknown-message-type")
            .end();

        // ====================================================================
        // MESSAGE AGGREGATOR
        // ====================================================================
        from("direct:aggregate-messages")
            .routeId("message-aggregator")
            .log("Adding to aggregation: ${header.correlationId}/${header.aggregationKey}")
            .bean("integrationService", "addToAggregation")
            .choice()
                .when(simple("${body.complete}"))
                    .log("Aggregation complete for ${header.correlationId}")
                    .bean("integrationService", "getAggregatedResult")
                    .to("direct:process-aggregated")
            .end();

        from("direct:process-aggregated")
            .routeId("process-aggregated")
            .log("Processing aggregated result")
            .bean("integrationService", "processAggregatedResult");

        // ====================================================================
        // RETRY PROCESSOR - Timer-based retry
        // ====================================================================
        from("timer:retry-processor?period=60000")
            .routeId("retry-processor")
            .log("Processing pending retries")
            .bean("integrationService", "getPendingRetries")
            .split(body())
                .log("Retrying message: ${body.messageId}")
                .to("direct:route-message")
            .end();

        // ====================================================================
        // AGGREGATION TIMEOUT PROCESSOR
        // ====================================================================
        from("timer:aggregation-timeout?period=30000")
            .routeId("aggregation-timeout-processor")
            .log("Processing aggregation timeouts")
            .bean("integrationService", "processAggregationTimeouts");

        // ====================================================================
        // UNKNOWN MESSAGE HANDLER
        // ====================================================================
        from("seda:unknown-message-type")
            .routeId("unknown-message-handler")
            .log("Storing unknown message type for review: ${header.messageType}")
            .bean("integrationService", "storeUnknownMessage");
    }
}
