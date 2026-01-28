package com.gprintex.clm;

import com.gprintex.clm.domain.Contract;
import com.gprintex.clm.domain.Customer;
import com.gprintex.clm.domain.ValidationResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for domain models.
 */
class DomainModelTests {

    @Test
    void contractDraft_shouldCreateWithRequiredFields() {
        var contract = Contract.draft("TENANT1", "CNT-001", 100L, LocalDate.now());
        
        assertEquals("TENANT1", contract.tenantId());
        assertEquals("CNT-001", contract.contractNumber());
        assertEquals(100L, contract.customerId());
        assertEquals("DRAFT", contract.status());
        assertTrue(contract.id().isEmpty());
    }

    @Test
    void contractWithId_shouldReturnNewInstanceWithId() {
        var contract = Contract.draft("TENANT1", "CNT-001", 100L, LocalDate.now());
        var withId = contract.withId(999L);
        
        assertTrue(withId.id().isPresent());
        assertEquals(999L, withId.id().get());
        assertEquals(contract.contractNumber(), withId.contractNumber());
    }

    @Test
    void contractWithStatus_shouldReturnNewInstanceWithStatus() {
        var contract = Contract.draft("TENANT1", "CNT-001", 100L, LocalDate.now());
        var activated = contract.withStatus("ACTIVE");
        
        assertEquals("ACTIVE", activated.status());
        assertEquals("DRAFT", contract.status()); // Original unchanged
    }

    @Test
    void contract_shouldThrowOnMissingRequiredFields() {
        assertThrows(IllegalArgumentException.class, () ->
            Contract.draft(null, "CNT-001", 100L, LocalDate.now())
        );
        
        assertThrows(IllegalArgumentException.class, () ->
            Contract.draft("TENANT1", null, 100L, LocalDate.now())
        );
        
        assertThrows(IllegalArgumentException.class, () ->
            Contract.draft("TENANT1", "CNT-001", null, LocalDate.now())
        );
    }

    @Test
    void customerIndividual_shouldCreateWithDefaults() {
        var customer = Customer.individual("TENANT1", "CUST-001", "John Doe");
        
        assertEquals("TENANT1", customer.tenantId());
        assertEquals("CUST-001", customer.customerCode());
        assertEquals("John Doe", customer.name());
        assertEquals("INDIVIDUAL", customer.customerType());
        assertTrue(customer.active());
    }

    @Test
    void customerCompany_shouldIncludeTaxId() {
        var customer = Customer.company("TENANT1", "CUST-002", "Acme Corp", "12345678901234");
        
        assertEquals("COMPANY", customer.customerType());
        assertTrue(customer.taxId().isPresent());
        assertEquals("12345678901234", customer.taxId().get());
    }

    @Test
    void customerDeactivate_shouldReturnInactiveCustomer() {
        var customer = Customer.individual("TENANT1", "CUST-001", "John Doe");
        var deactivated = customer.deactivate();
        
        assertFalse(deactivated.active());
        assertTrue(customer.active()); // Original unchanged
    }

    @Test
    void validationResult_success_shouldBeValid() {
        var result = ValidationResult.success();
        
        assertTrue(result.valid());
        assertNull(result.errorCode());
    }

    @Test
    void validationResult_error_shouldContainDetails() {
        var result = ValidationResult.error("REQUIRED", "Field is required", "name");
        
        assertFalse(result.valid());
        assertEquals("REQUIRED", result.errorCode());
        assertEquals("Field is required", result.errorMessage());
        assertEquals("name", result.fieldName());
    }
}
