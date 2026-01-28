package com.gprintex.clm.api;

import com.gprintex.clm.domain.TransformMetadata;
import com.gprintex.clm.service.EtlService;
import org.apache.camel.ProducerTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API controller for ETL operations.
 * Triggers Camel ETL routes.
 */
@RestController
@RequestMapping("/api/v1/etl")
public class EtlController {

    private final EtlService etlService;
    private final ProducerTemplate producerTemplate;

    public EtlController(EtlService etlService, ProducerTemplate producerTemplate) {
        this.etlService = etlService;
        this.producerTemplate = producerTemplate;
    }

    @PostMapping("/contracts/ingest")
    public ResponseEntity<?> ingestContracts(
        @RequestBody List<Map<String, Object>> contracts,
        @RequestHeader("X-Source-System") String sourceSystem
    ) {
        try {
            // Trigger Camel route
            var result = producerTemplate.requestBodyAndHeaders(
                "direct:contract-ingest",
                contracts,
                Map.of("sourceSystem", sourceSystem),
                TransformMetadata.class
            );
            
            return ResponseEntity.ok(result);
        } catch (org.apache.camel.CamelExecutionException e) {
            // Log and return safe error response
            return ResponseEntity.status(502).body(Map.of(
                "error", "ETL processing failed",
                "sourceSystem", sourceSystem
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Unexpected error during ETL processing"
            ));
        }
    }

    @PostMapping("/customers/ingest")
    public ResponseEntity<?> ingestCustomers(
        @RequestBody List<Map<String, Object>> customers,
        @RequestHeader("X-Source-System") String sourceSystem
    ) {
        var result = producerTemplate.requestBodyAndHeaders(
            "direct:customer-ingest",
            customers,
            Map.of("sourceSystem", sourceSystem),
            TransformMetadata.class
        );
        
        return ResponseEntity.ok(result);
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<?> getSessionStatus(@PathVariable String sessionId) {
        var status = etlService.getSessionStatus(sessionId);
        
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.ok(Map.of(
            "sessionId", sessionId,
            "status", status
        ));
    }

    @PostMapping("/sessions/{sessionId}/complete")
    public ResponseEntity<?> completeSession(@PathVariable String sessionId) {
        // Validate session exists
        var status = etlService.getSessionStatus(sessionId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        
        etlService.completeSession(sessionId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/sessions/{sessionId}/fail")
    public ResponseEntity<?> failSession(
        @PathVariable String sessionId,
        @RequestBody Map<String, String> body
    ) {
        // Validate session exists
        var status = etlService.getSessionStatus(sessionId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        
        var errorMessage = body.getOrDefault("error", "Manual failure");
        etlService.failSession(sessionId, errorMessage);
        return ResponseEntity.ok().build();
    }
}
