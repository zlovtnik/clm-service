package com.gprintex.clm.api;

import com.gprintex.clm.domain.TransformMetadata;
import com.gprintex.clm.service.EtlService;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API controller for ETL operations.
 * Triggers Camel ETL routes and provides direct access to ETL_PKG operations.
 */
@RestController
@RequestMapping("/api/v1/etl")
public class EtlController {

    private static final Logger log = LoggerFactory.getLogger(EtlController.class);

    private final EtlService etlService;
    private final ProducerTemplate producerTemplate;

    public EtlController(EtlService etlService, ProducerTemplate producerTemplate) {
        this.etlService = etlService;
        this.producerTemplate = producerTemplate;
    }

    // ========================================================================
    // SESSION MANAGEMENT
    // ========================================================================

    @PostMapping("/sessions")
    public ResponseEntity<?> createSession(@RequestBody Map<String, String> body) {
        var sourceSystem = body.get("p_source_system");
        var entityType = body.get("p_entity_type");
        
        if (sourceSystem == null || entityType == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "p_source_system and p_entity_type are required"
            ));
        }
        
        var sessionId = etlService.createSession(sourceSystem, entityType);
        return ResponseEntity.ok(Map.of("p_session_id", sessionId));
    }

    @GetMapping("/sessions")
    public ResponseEntity<?> getActiveSessions() {
        var sessions = etlService.getActiveSessions();
        return ResponseEntity.ok(sessions);
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<?> getSessionStatus(@PathVariable String sessionId) {
        try {
            var status = etlService.getSessionStatus(sessionId);
            if (status == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "status", status
            ));
        } catch (Exception e) {
            log.error("Error getting session status for sessionId: {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to get session status"));
        }
    }

    @GetMapping("/sessions/{sessionId}/info")
    public ResponseEntity<?> getSessionInfo(@PathVariable String sessionId) {
        try {
            var info = etlService.getSessionInfo(sessionId);
            if (info == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(info);
        } catch (Exception e) {
            log.error("Error getting session info for sessionId: {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to get session info"));
        }
    }

    @GetMapping("/sessions/{sessionId}/audit")
    public ResponseEntity<?> getSessionAuditTrail(@PathVariable String sessionId) {
        try {
            var audit = etlService.getSessionAuditTrail(sessionId);
            return ResponseEntity.ok(audit);
        } catch (Exception e) {
            log.error("Error getting session audit trail for sessionId: {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to get session audit trail"));
        }
    }

    @PostMapping("/sessions/{sessionId}/complete")
    public ResponseEntity<?> completeSession(@PathVariable String sessionId) {
        try {
            var status = etlService.getSessionStatus(sessionId);
            if (status == null) {
                return ResponseEntity.notFound().build();
            }
            etlService.completeSession(sessionId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Error completing session: {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to complete session"));
        }
    }

    @PostMapping("/sessions/{sessionId}/fail")
    public ResponseEntity<?> failSession(
        @PathVariable String sessionId,
        @RequestBody Map<String, String> body
    ) {
        try {
            var status = etlService.getSessionStatus(sessionId);
            if (status == null) {
                return ResponseEntity.notFound().build();
            }
            var errorMessage = body.getOrDefault("p_error_message", body.getOrDefault("error", "Manual failure"));
            etlService.failSession(sessionId, errorMessage);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Error failing session: {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to fail session"));
        }
    }

    @PostMapping("/sessions/{sessionId}/rollback")
    public ResponseEntity<?> rollbackSession(@PathVariable String sessionId) {
        try {
            var status = etlService.getSessionStatus(sessionId);
            if (status == null) {
                return ResponseEntity.notFound().build();
            }
            etlService.rollbackSession(sessionId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Failed to rollback session"
            ));
        }
    }

    @PostMapping("/sessions/cleanup")
    public ResponseEntity<?> cleanupOldSessions(@RequestBody Map<String, Object> body) {
        var retentionDays = body.get("p_retention_days");
        int days;
        
        // Default to 7 if not provided
        if (retentionDays == null) {
            days = 7;
        } else if (retentionDays instanceof Number n) {
            days = n.intValue();
        } else {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "p_retention_days must be a positive integer"
            ));
        }
        
        // Validate retention days is positive
        if (days <= 0) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "p_retention_days must be a positive integer (> 0)"
            ));
        }
        
        var deletedCount = etlService.cleanupOldSessions(days);
        return ResponseEntity.ok(Map.of("p_deleted_count", deletedCount));
    }

    // ========================================================================
    // DATA LOADING
    // ========================================================================

    @PostMapping("/sessions/{sessionId}/load")
    public ResponseEntity<?> loadToStaging(
        @PathVariable String sessionId,
        @RequestBody Map<String, Object> body
    ) {
        try {
            var data = body.get("p_data");
            var format = (String) body.getOrDefault("p_format", "JSON");
            
            if (data instanceof List<?> records) {
                etlService.loadToStaging(sessionId, records);
            } else if (data instanceof String json) {
                // Parse JSON string to list and load
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var records = mapper.readValue(json, List.class);
                etlService.loadToStaging(sessionId, records);
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "p_data must be a JSON array or string"
                ));
            }
            
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Failed to load data to staging"
            ));
        }
    }

    @PostMapping("/sessions/{sessionId}/load-record")
    public ResponseEntity<?> loadRecordToStaging(
        @PathVariable String sessionId,
        @RequestBody Map<String, Object> body
    ) {
        try {
            var rawData = body.get("p_raw_data");
            String json;
            
            if (rawData instanceof String s) {
                json = s;
            } else {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                json = mapper.writeValueAsString(rawData);
            }
            
            var seqNum = etlService.loadRecordToStaging(sessionId, json);
            return ResponseEntity.ok(Map.of("p_seq_num", seqNum));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Failed to load record to staging"
            ));
        }
    }

    // ========================================================================
    // TRANSFORMATION
    // ========================================================================

    @PostMapping("/sessions/{sessionId}/transform/contracts")
    public ResponseEntity<?> transformContracts(
        @PathVariable String sessionId,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        try {
            String rules = null;
            if (body != null && body.containsKey("p_transformation_rules")) {
                var rulesObj = body.get("p_transformation_rules");
                if (rulesObj instanceof String s) {
                    rules = s;
                } else if (rulesObj != null) {
                    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    rules = mapper.writeValueAsString(rulesObj);
                }
            }
            
            var result = etlService.transformContracts(sessionId, rules);
            return ResponseEntity.ok(Map.of("p_results", transformMetadataToMap(result)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Failed to transform contracts"
            ));
        }
    }

    @PostMapping("/sessions/{sessionId}/transform/customers")
    public ResponseEntity<?> transformCustomers(
        @PathVariable String sessionId,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        try {
            String rules = null;
            if (body != null && body.containsKey("p_transformation_rules")) {
                var rulesObj = body.get("p_transformation_rules");
                if (rulesObj instanceof String s) {
                    rules = s;
                } else if (rulesObj != null) {
                    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    rules = mapper.writeValueAsString(rulesObj);
                }
            }
            
            var result = etlService.transformCustomers(sessionId, rules);
            return ResponseEntity.ok(Map.of("p_results", transformMetadataToMap(result)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Failed to transform customers"
            ));
        }
    }

    @PostMapping("/sessions/{sessionId}/apply-rules")
    public ResponseEntity<?> applyBusinessRules(
        @PathVariable String sessionId,
        @RequestBody Map<String, String> body
    ) {
        try {
            var ruleSet = body.getOrDefault("p_rule_set", "DEFAULT");
            etlService.applyBusinessRules(sessionId, ruleSet);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Failed to apply business rules"
            ));
        }
    }

    // ========================================================================
    // VALIDATION
    // ========================================================================

    @PostMapping("/sessions/{sessionId}/validate")
    public ResponseEntity<?> validateStagingData(@PathVariable String sessionId) {
        try {
            var result = etlService.validateStaging(sessionId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Failed to validate staging data"
            ));
        }
    }

    @GetMapping("/sessions/{sessionId}/validation-summary")
    public ResponseEntity<?> getValidationSummary(@PathVariable String sessionId) {
        try {
            var summary = etlService.getValidationSummary(sessionId);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            log.error("Error getting validation summary for sessionId: {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to get validation summary"));
        }
    }

    // ========================================================================
    // PROMOTION
    // ========================================================================

    @PostMapping("/sessions/{sessionId}/promote/contracts")
    public ResponseEntity<?> promoteContracts(
        @PathVariable String sessionId,
        @RequestBody(required = false) Map<String, String> body
    ) {
        try {
            var user = body != null ? body.getOrDefault("p_user", "SYSTEM") : "SYSTEM";
            var result = etlService.promoteContracts(sessionId, user);
            return ResponseEntity.ok(Map.of("p_results", transformMetadataToMap(result)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Failed to promote contracts"
            ));
        }
    }

    @PostMapping("/sessions/{sessionId}/promote/customers")
    public ResponseEntity<?> promoteCustomers(
        @PathVariable String sessionId,
        @RequestBody(required = false) Map<String, String> body
    ) {
        try {
            var user = body != null ? body.getOrDefault("p_user", "SYSTEM") : "SYSTEM";
            var result = etlService.promoteCustomers(sessionId, user);
            return ResponseEntity.ok(Map.of("p_results", transformMetadataToMap(result)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Failed to promote customers"
            ));
        }
    }

    @PostMapping("/sessions/{sessionId}/promote")
    public ResponseEntity<?> promoteFromStaging(
        @PathVariable String sessionId,
        @RequestBody Map<String, String> body
    ) {
        try {
            var targetTable = body.get("p_target_table");
            var user = body.getOrDefault("p_user", "SYSTEM");
            
            if (targetTable == null) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "p_target_table is required"
                ));
            }
            
            var result = etlService.promoteFromStaging(sessionId, targetTable, user);
            return ResponseEntity.ok(Map.of("p_results", transformMetadataToMap(result)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Failed to promote from staging"
            ));
        }
    }

    // ========================================================================
    // CAMEL ROUTE INTEGRATION (High-level ETL pipelines)
    // ========================================================================

    @PostMapping("/contracts/ingest")
    public ResponseEntity<?> ingestContracts(
        @RequestBody List<Map<String, Object>> contracts,
        @RequestHeader("X-Source-System") String sourceSystem
    ) {
        try {
            var result = producerTemplate.requestBodyAndHeaders(
                "direct:contract-ingest",
                contracts,
                Map.of("sourceSystem", sourceSystem),
                TransformMetadata.class
            );
            
            return ResponseEntity.ok(result);
        } catch (org.apache.camel.CamelExecutionException e) {
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
        try {
            var result = producerTemplate.requestBodyAndHeaders(
                "direct:customer-ingest",
                customers,
                Map.of("sourceSystem", sourceSystem),
                TransformMetadata.class
            );
            
            return ResponseEntity.ok(result);
        } catch (org.apache.camel.CamelExecutionException e) {
            return ResponseEntity.status(502).body(Map.of(
                "error", "Upstream ETL failure",
                "details", e.getMessage()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Failed to ingest customers"
            ));
        }
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private Map<String, Object> transformMetadataToMap(TransformMetadata metadata) {
        return Map.of(
            "source_system", metadata.sourceSystem(),
            "transform_timestamp", metadata.transformTimestamp().toString(),
            "transform_version", metadata.transformVersion(),
            "record_count", metadata.recordCount(),
            "success_count", metadata.successCount(),
            "error_count", metadata.errorCount()
        );
    }
}
