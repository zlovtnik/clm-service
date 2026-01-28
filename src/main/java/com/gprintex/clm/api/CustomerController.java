package com.gprintex.clm.api;

import com.gprintex.clm.domain.Customer;
import com.gprintex.clm.domain.ValidationResult;
import com.gprintex.clm.service.CustomerService;
import com.gprintex.clm.repository.CustomerRepository.CustomerFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API controller for Customer operations.
 */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    public ResponseEntity<?> create(
        @RequestBody Customer customer,
        @RequestHeader("X-User-Id") String userId,
        @RequestHeader("X-Tenant-Id") String tenantId
    ) {
        // Always use header tenantId as authoritative - preserves all other customer fields
        var effectiveCustomer = customer.withTenantId(tenantId);
        
        var result = customerService.create(effectiveCustomer, userId);
        
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
        return customerService.findById(tenantId, id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/code/{customerCode}")
    public ResponseEntity<?> getByCode(
        @PathVariable String customerCode,
        @RequestHeader("X-Tenant-Id") String tenantId
    ) {
        return customerService.findByCode(tenantId, customerCode)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<Customer>> list(
        @RequestHeader("X-Tenant-Id") String tenantId,
        @RequestParam(required = false) Boolean active,
        @RequestParam(required = false) String type,
        @RequestParam(required = false) String search
    ) {
        var filter = CustomerFilter.forTenant(tenantId);
        // Handle both active=true and active=false cases
        if (active != null) {
            filter = Boolean.TRUE.equals(active) ? filter.onlyActive() : filter.onlyInactive();
        }
        if (type != null) filter = filter.withType(type);
        if (search != null) filter = filter.search(search);
        
        var customers = customerService.findByFilter(filter);
        return ResponseEntity.ok(customers);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
        @PathVariable Long id,
        @RequestBody Customer customer,
        @RequestHeader("X-User-Id") String userId,
        @RequestHeader("X-Tenant-Id") String tenantId
    ) {
        // Ensure path ID is authoritative
        var customerWithId = customer.withId(id);
        
        var result = customerService.update(customerWithId, userId);
        
        return result.fold(
            error -> ResponseEntity.badRequest().body(error),
            ResponseEntity::ok
        );
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<?> activate(
        @PathVariable Long id,
        @RequestHeader("X-Tenant-Id") String tenantId,
        @RequestHeader("X-User-Id") String userId
    ) {
        var result = customerService.activate(tenantId, id, userId);
        
        return result.fold(
            error -> {
                var message = error.getMessage();
                if (message != null && message.contains("not found")) {
                    return ResponseEntity.notFound().build();
                } else if (message != null && (message.contains("already active") || message.contains("invalid"))) {
                    return ResponseEntity.badRequest().body(message);
                }
                return ResponseEntity.internalServerError().body(message);
            },
            success -> ResponseEntity.ok().build()
        );
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<?> deactivate(
        @PathVariable Long id,
        @RequestHeader("X-Tenant-Id") String tenantId,
        @RequestHeader("X-User-Id") String userId
    ) {
        var result = customerService.deactivate(tenantId, id, userId);
        
        return result.fold(
            error -> {
                var message = error.getMessage();
                if (message != null && message.contains("not found")) {
                    return ResponseEntity.notFound().build();
                } else if (message != null && (message.contains("already inactive") || message.contains("invalid"))) {
                    return ResponseEntity.badRequest().body(message);
                }
                return ResponseEntity.internalServerError().body(message);
            },
            success -> ResponseEntity.ok().build()
        );
    }

    @PostMapping("/validate")
    public ResponseEntity<List<ValidationResult>> validate(@RequestBody Customer customer) {
        var results = customerService.validate(customer);
        return ResponseEntity.ok(results);
    }

    /**
     * Request record for tax ID validation.
     */
    public record TaxIdValidationRequest(String taxId, String customerType) {}

    @PostMapping("/validate/tax-id")
    public ResponseEntity<ValidationResult> validateTaxId(
        @RequestBody TaxIdValidationRequest request
    ) {
        var result = customerService.validateTaxId(request.taxId(), request.customerType());
        return ResponseEntity.ok(result);
    }
}
