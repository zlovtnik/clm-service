package com.gprintex.clm.camel;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * Camel route for Contract CRUD operations via REST-like direct endpoints.
 * Uses functional processing with validation.
 */
@Component
public class ContractCrudRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {

        // ====================================================================
        // CREATE CONTRACT
        // ====================================================================
        from("direct:contract-create")
            .routeId("contract-create")
            .log("Creating contract: ${body.contractNumber}")
            .bean("contractService", "create")
            .choice()
                .when(simple("${body.isLeft}"))
                    .log("Contract creation failed: ${body.left}")
                    .setHeader("error", simple("${body.left}"))
                    .transform(simple("${body.left}"))
                .otherwise()
                    .log("Contract created with ID: ${body.right.id}")
                    .transform(simple("${body.right}"))
                    .to("seda:contract-created-event")
            .end();

        // ====================================================================
        // READ CONTRACT
        // ====================================================================
        from("direct:contract-get")
            .routeId("contract-get")
            .log("Getting contract: ${header.tenantId}/${header.id}")
            .bean("contractService", "findById(${header.tenantId}, ${header.id})")
            .choice()
                .when(simple("${body.isEmpty}"))
                    .log("Contract not found")
                    .setHeader("error", constant("NOT_FOUND"))
                .otherwise()
                    .transform(simple("${body.get}"))
            .end();

        from("direct:contract-get-by-number")
            .routeId("contract-get-by-number")
            .log("Getting contract by number: ${header.tenantId}/${header.contractNumber}")
            .bean("contractService", "findByNumber(${header.tenantId}, ${header.contractNumber})")
            .choice()
                .when(simple("${body.isEmpty}"))
                    .setHeader("error", constant("NOT_FOUND"))
                .otherwise()
                    .transform(simple("${body.get}"))
            .end();

        from("direct:contract-list")
            .routeId("contract-list")
            .log("Listing contracts for tenant: ${header.tenantId}")
            .doTry()
                .bean("contractService", "findByFilter")
                .transform(simple("${body.toList}"))
            .doCatch(Exception.class)
                .log("Error listing contracts: ${exception.message}")
                .setHeader("errorCode", constant("LIST_FAILED"))
                .setHeader("errorMessage", simple("${exception.message}"))
                .setBody(constant(java.util.Collections.emptyList()))
            .end();

        // ====================================================================
        // UPDATE CONTRACT
        // ====================================================================
        from("direct:contract-update")
            .routeId("contract-update")
            .log("Updating contract: ${body.id}")
            .bean("contractService", "update")
            .choice()
                .when(simple("${body.isLeft}"))
                    .log("Contract update failed: ${body.left}")
                    .setHeader("error", simple("${body.left}"))
                    .transform(simple("${body.left}"))
                .otherwise()
                    .log("Contract updated")
                    .transform(simple("${body.right}"))
                    .to("seda:contract-updated-event")
            .end();

        from("direct:contract-update-status")
            .routeId("contract-update-status")
            .log("Updating contract status: ${header.id} -> ${header.newStatus}")
            .bean("contractService", "updateStatus(${header.tenantId}, ${header.id}, ${header.newStatus}, ${header.user}, ${header.reason})")
            .choice()
                .when(simple("${body.isLeft}"))
                    .log("Status update failed: ${body.left}")
                    .setHeader("error", simple("${body.left}"))
                    .transform(simple("${body.left}"))
                .otherwise()
                    .transform(simple("${body.right}"))
                    .to("seda:contract-status-changed-event")
            .end();

        // ====================================================================
        // DELETE CONTRACT (Soft delete)
        // ====================================================================
        from("direct:contract-delete")
            .routeId("contract-delete")
            .log("Deleting contract: ${header.tenantId}/${header.id}")
            .bean("contractService", "softDelete(${header.tenantId}, ${header.id}, ${header.user}, ${header.reason})")
            .choice()
                .when(simple("${body.isFailure}"))
                    .log("Contract deletion failed: ${body.cause.message}")
                    .setHeader("error", simple("${body.cause.message}"))
                    .setBody(simple("${body.cause.message}"))
                .otherwise()
                    .log("Contract deleted")
                    .to("seda:contract-deleted-event")
            .end();

        // ====================================================================
        // EVENT EMISSION
        // ====================================================================
        from("seda:contract-created-event")
            .routeId("contract-created-event")
            .setHeader("messageType", constant("CONTRACT_CREATED"))
            .bean("eventPublisher", "publish");

        from("seda:contract-updated-event")
            .routeId("contract-updated-event")
            .setHeader("messageType", constant("CONTRACT_UPDATED"))
            .bean("eventPublisher", "publish");

        from("seda:contract-status-changed-event")
            .routeId("contract-status-changed-event")
            .setHeader("messageType", constant("CONTRACT_STATUS_CHANGED"))
            .bean("eventPublisher", "publish");

        from("seda:contract-deleted-event")
            .routeId("contract-deleted-event")
            .setHeader("messageType", constant("CONTRACT_DELETED"))
            .bean("eventPublisher", "publish");
    }
}
