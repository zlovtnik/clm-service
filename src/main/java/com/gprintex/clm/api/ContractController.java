package com.gprintex.clm.api;

import com.gprintex.clm.domain.Contract;
import com.gprintex.clm.domain.ValidationResult;
import com.gprintex.clm.service.ContractService;
import com.gprintex.clm.repository.ContractRepository.ContractFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API controller for Contract operations.
 * Delegates to Camel routes for processing.
 */
@RestController
@RequestMapping("/api/v1/contracts")
public class ContractController {

    private final ContractService contractService;

    public ContractController(ContractService contractService) {
        this.contractService = contractService;
    }

    @PostMapping
    public ResponseEntity<?> create(
        @RequestBody Contract contract,
        @RequestHeader("X-User-Id") String userId,
        @RequestHeader("X-Tenant-Id") String tenantId
    ) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "X-Tenant-Id header is required"));
        }
        // Validate tenant: if both header and body have tenantId, they must match
        if (tenantId != null && contract.tenantId() != null && !contract.tenantId().isBlank() 
            && !tenantId.equals(contract.tenantId())) {
            return ResponseEntity.badRequest().body(
                Map.of("error", "Tenant mismatch: header X-Tenant-Id does not match contract.tenantId"));
        }
        
        // Use header tenantId if contract doesn't have one; preserve all other fields
        Contract effectiveContract;
        if (tenantId != null && (contract.tenantId() == null || contract.tenantId().isBlank())) {
            effectiveContract = contract.withTenantId(tenantId);
        } else {
            effectiveContract = contract;
        }
        
        var result = contractService.create(effectiveContract, userId);
        
        return result.fold(
            errors -> ResponseEntity.badRequest().body(errors),
            ResponseEntity::ok
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(
        @PathVariable Long id,
        @RequestHeader("X-Tenant-Id") String tenantId
    ) {
        return contractService.findById(tenantId, id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/number/{contractNumber}")
    public ResponseEntity<?> getByNumber(
        @PathVariable String contractNumber,
        @RequestHeader("X-Tenant-Id") String tenantId
    ) {
        return contractService.findByNumber(tenantId, contractNumber)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<Contract>> list(
        @RequestHeader("X-Tenant-Id") String tenantId,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) Long customerId
    ) {
        var filter = ContractFilter.forTenant(tenantId);
        if (status != null) filter = filter.withStatus(status);
        if (customerId != null) filter = filter.withCustomer(customerId);
        
        var contracts = contractService.findByFilter(filter);
        return ResponseEntity.ok(contracts);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
        @PathVariable Long id,
        @RequestBody Contract contract,
        @RequestHeader("X-User-Id") String userId,
        @RequestHeader("X-Tenant-Id") String tenantId
    ) {
        // Ensure path ID is authoritative - override any ID in request body
        var contractWithId = contract.withId(id);
        
        // Enforce tenant scoping: set tenant from header to ensure tenant isolation
        var contractWithTenant = contractWithId.withTenantId(tenantId);
        
        var result = contractService.update(contractWithTenant, userId);
        
        return result.fold(
            error -> ResponseEntity.badRequest().body(error),
            ResponseEntity::ok
        );
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(
        @PathVariable Long id,
        @RequestHeader("X-Tenant-Id") String tenantId,
        @RequestHeader("X-User-Id") String userId,
        @RequestBody Map<String, String> body
    ) {
        var newStatus = body.get("status");
        
        // Validate required 'status' field
        if (newStatus == null || newStatus.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "'status' is required"));
        }
        
        var reason = body.get("reason");
        
        var result = contractService.updateStatus(tenantId, id, newStatus, userId, reason);
        
        return result.fold(
            error -> ResponseEntity.badRequest().body(error),
            ResponseEntity::ok
        );
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
        @PathVariable Long id,
        @RequestHeader("X-Tenant-Id") String tenantId,
        @RequestHeader("X-User-Id") String userId,
        @RequestParam(required = false) String reason
    ) {
        var result = contractService.softDelete(tenantId, id, userId, reason);
        
        return result.fold(
            error -> {
                var message = error.getMessage();
                // Map error types to appropriate HTTP statuses
                if (message != null && message.contains("not found")) {
                    return ResponseEntity.notFound().build();
                } else if (message != null && (message.contains("invalid") || message.contains("cannot"))) {
                    return ResponseEntity.badRequest().body(message);
                } else if (message != null && message.contains("conflict")) {
                    return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT).body(message);
                }
                return ResponseEntity.internalServerError().body(message);
            },
            success -> ResponseEntity.noContent().build()
        );
    }

    @GetMapping("/{id}/transitions")
    public ResponseEntity<List<String>> getAllowedTransitions(
        @PathVariable Long id,
        @RequestHeader("X-Tenant-Id") String tenantId
    ) {
        return contractService.findById(tenantId, id)
            .map(contract -> contractService.getAllowedTransitions(contract.status()))
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/validate")
    public ResponseEntity<List<ValidationResult>> validate(@RequestBody Contract contract) {
        var results = contractService.validate(contract);
        return ResponseEntity.ok(results);
    }
}
