package com.gprintex.clm.camel;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * Camel route for Contract ETL operations.
 * Implements EIP patterns: Content-Based Router, Splitter, Aggregator.
 */
@Component
public class ContractEtlRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {
        
        // Error handling with dead letter channel
        errorHandler(deadLetterChannel("seda:contract-dlq")
            .maximumRedeliveries(3)
            .redeliveryDelay(1000)
            .backOffMultiplier(2)
            .useExponentialBackOff()
            .logStackTrace(true));

        // ====================================================================
        // INGEST ROUTE - Entry point for contract data
        // ====================================================================
        from("direct:contract-ingest")
            .routeId("contract-ingest")
            .log("Ingesting contract batch from ${header.sourceSystem}")
            .setHeader("sessionId", method("etlService", "createSession(${header.sourceSystem}, 'CONTRACT')"))
            .to("direct:contract-stage");

        // ====================================================================
        // STAGING ROUTE - Load to staging table
        // ====================================================================
        from("direct:contract-stage")
            .routeId("contract-stage")
            .log("Staging ${body.size()} contracts for session ${header.sessionId}")
            .bean("etlService", "loadToStaging(${header.sessionId}, ${body})")
            .to("direct:contract-transform");

        // ====================================================================
        // TRANSFORM ROUTE - Apply transformation rules
        // ====================================================================
        from("direct:contract-transform")
            .routeId("contract-transform")
            .log("Transforming contracts for session ${header.sessionId}")
            .bean("etlService", "transformContracts(${header.sessionId})")
            .to("direct:contract-validate");

        // ====================================================================
        // VALIDATION ROUTE - Validate transformed data
        // ====================================================================
        from("direct:contract-validate")
            .routeId("contract-validate")
            .log("Validating contracts for session ${header.sessionId}")
            .bean("etlService", "validateStaging(${header.sessionId})")
            .choice()
                .when(simple("${body.hasErrors}"))
                    .log("Validation errors found: ${body.errorCount}")
                    .to("seda:contract-validation-errors")
                    .to("direct:contract-complete") // Finalize session after handling errors
                .otherwise()
                    .to("direct:contract-promote")
            .end();

        // ====================================================================
        // PROMOTE ROUTE - Move from staging to target
        // ====================================================================
        from("direct:contract-promote")
            .routeId("contract-promote")
            .log("Promoting contracts from session ${header.sessionId}")
            .bean("etlService", "promoteContracts(${header.sessionId})")
            .to("direct:contract-complete");

        // ====================================================================
        // COMPLETION ROUTE - Finalize ETL session
        // ====================================================================
        from("direct:contract-complete")
            .routeId("contract-complete")
            .log("Completing ETL session ${header.sessionId}: ${body.successCount}/${body.recordCount} successful")
            .bean("etlService", "completeSession(${header.sessionId})")
            .to("seda:contract-etl-complete");

        // ====================================================================
        // ERROR HANDLING ROUTES
        // ====================================================================
        from("seda:contract-validation-errors")
            .routeId("contract-validation-errors")
            .log("Processing validation errors for session ${header.sessionId}")
            .bean("etlService", "handleValidationErrors(${header.sessionId}, ${body})");

        from("seda:contract-dlq")
            .routeId("contract-dlq")
            .log("Contract ETL failed: ${exchangeProperty.CamelExceptionCaught.message}")
            .choice()
                .when(header("sessionId").isNotNull())
                    .bean("etlService", "failSession(${header.sessionId}, ${exchangeProperty.CamelExceptionCaught.message})")
                .otherwise()
                    .log("DLQ: No sessionId available, cannot mark session as failed")
            .end();

        from("seda:contract-etl-complete")
            .routeId("contract-etl-notify")
            .log("ETL Complete notification for session ${header.sessionId}")
            .bean("integrationService", "notifyEtlComplete(${header.sessionId}, ${body})");
    }
}
