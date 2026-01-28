package com.gprintex.clm.camel;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * Camel route for Customer ETL operations.
 * Supports upsert (insert or update) pattern.
 */
@Component
public class CustomerEtlRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {

        errorHandler(deadLetterChannel("seda:customer-dlq")
            .maximumRedeliveries(3)
            .redeliveryDelay(1000)
            .backOffMultiplier(2)
            .useExponentialBackOff());

        // ====================================================================
        // CUSTOMER INGEST
        // ====================================================================
        from("direct:customer-ingest")
            .routeId("customer-ingest")
            .log("Ingesting customer batch from ${header.sourceSystem}")
            .setHeader("sessionId", method("etlService", "createSession(${header.sourceSystem}, 'CUSTOMER')"))
            .to("direct:customer-stage");

        // ====================================================================
        // CUSTOMER STAGING
        // ====================================================================
        from("direct:customer-stage")
            .routeId("customer-stage")
            .process(exchange -> {
                var body = exchange.getIn().getBody();
                int safeSize = 0;
                if (body != null) {
                    if (body instanceof java.util.Collection) {
                        safeSize = ((java.util.Collection<?>) body).size();
                    } else if (body.getClass().isArray()) {
                        safeSize = java.lang.reflect.Array.getLength(body);
                    } else {
                        safeSize = 1;
                    }
                }
                exchange.getIn().setHeader("customerCount", safeSize);
            })
            .log("Staging ${header.customerCount} customers for session ${header.sessionId}")
            .bean("etlService", "loadToStaging(${header.sessionId}, ${body})")
            .to("direct:customer-transform");

        // ====================================================================
        // CUSTOMER TRANSFORM
        // ====================================================================
        from("direct:customer-transform")
            .routeId("customer-transform")
            .log("Transforming customers for session ${header.sessionId}")
            .bean("etlService", "transformCustomers(${header.sessionId})")
            .to("direct:customer-validate");

        // ====================================================================
        // CUSTOMER VALIDATION
        // ====================================================================
        from("direct:customer-validate")
            .routeId("customer-validate")
            .log("Validating customers for session ${header.sessionId}")
            .bean("etlService", "validateStaging(${header.sessionId})")
            .choice()
                .when(simple("${body.hasErrors}"))
                    .to("seda:customer-validation-errors")
                .otherwise()
                    .to("direct:customer-upsert")
            .end();

        // ====================================================================
        // CUSTOMER UPSERT (merge)
        // ====================================================================
        from("direct:customer-upsert")
            .routeId("customer-upsert")
            .log("Upserting customers from session ${header.sessionId}")
            .bean("etlService", "promoteCustomers(${header.sessionId})")
            .to("direct:customer-complete");

        // ====================================================================
        // COMPLETION
        // ====================================================================
        from("direct:customer-complete")
            .routeId("customer-complete")
            .log("Completing customer ETL session ${header.sessionId}")
            .bean("etlService", "completeSession(${header.sessionId})")
            .to("seda:customer-etl-complete");

        // ====================================================================
        // ERROR HANDLING
        // ====================================================================
        from("seda:customer-validation-errors")
            .routeId("customer-validation-errors")
            .log("Processing customer validation errors")
            .bean("etlService", "handleValidationErrors(${header.sessionId}, ${body})");

        from("seda:customer-dlq")
            .routeId("customer-dlq")
            .log("Customer ETL failed: ${exchangeProperty.CamelExceptionCaught.message}")
            .choice()
                .when(header("sessionId").isNotNull())
                    .bean("etlService", "failSession(${header.sessionId}, ${exchangeProperty.CamelExceptionCaught.message})")
                .otherwise()
                    .log("DLQ: No sessionId available, cannot mark session as failed")
            .end();

        from("seda:customer-etl-complete")
            .routeId("customer-etl-notify")
            .log("Customer ETL complete notification")
            .bean("integrationService", "notifyEtlComplete(${header.sessionId}, ${body})");
    }
}
