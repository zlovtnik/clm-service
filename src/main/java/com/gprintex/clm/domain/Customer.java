package com.gprintex.clm.domain;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Customer domain record - immutable value object matching Oracle customer_t type.
 */
public record Customer(
    Optional<Long> id,
    String tenantId,
    String customerCode,
    String customerType,
    String name,
    Optional<String> tradeName,
    Optional<String> taxId,
    Optional<String> email,
    Optional<String> phone,
    Optional<String> addressStreet,
    Optional<String> addressCity,
    Optional<String> addressState,
    Optional<String> addressZip,
    Optional<String> addressCountry,
    boolean active,
    Optional<LocalDateTime> createdAt,
    Optional<LocalDateTime> updatedAt,
    Optional<String> createdBy,
    Optional<String> updatedBy
) {
    public Customer {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (customerCode == null || customerCode.isBlank()) {
            throw new IllegalArgumentException("customerCode is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (customerType == null) {
            customerType = CustomerType.INDIVIDUAL.name();
        } else {
            // Validate customerType against enum values
            try {
                CustomerType.valueOf(customerType);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid customerType: " + customerType + ". Must be one of: INDIVIDUAL, COMPANY");
            }
        }
        // Normalize Optional fields
        id = id != null ? id : Optional.empty();
        tradeName = tradeName != null ? tradeName : Optional.empty();
        taxId = taxId != null ? taxId : Optional.empty();
        email = email != null ? email : Optional.empty();
        phone = phone != null ? phone : Optional.empty();
        addressStreet = addressStreet != null ? addressStreet : Optional.empty();
        addressCity = addressCity != null ? addressCity : Optional.empty();
        addressState = addressState != null ? addressState : Optional.empty();
        addressZip = addressZip != null ? addressZip : Optional.empty();
        addressCountry = addressCountry != null ? addressCountry : Optional.empty();
        createdAt = createdAt != null ? createdAt : Optional.empty();
        updatedAt = updatedAt != null ? updatedAt : Optional.empty();
        createdBy = createdBy != null ? createdBy : Optional.empty();
        updatedBy = updatedBy != null ? updatedBy : Optional.empty();
    }

    /**
     * Factory for creating a new individual customer.
     */
    public static Customer individual(String tenantId, String customerCode, String name) {
        return new Customer(
            Optional.empty(), tenantId, customerCode, CustomerType.INDIVIDUAL.name(),
            name, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            true, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()
        );
    }

    /**
     * Factory for creating a new company customer.
     */
    public static Customer company(String tenantId, String customerCode, String name, String taxId) {
        if (taxId == null) {
            throw new IllegalArgumentException("taxId is required for company customers");
        }
        return new Customer(
            Optional.empty(), tenantId, customerCode, CustomerType.COMPANY.name(),
            name, Optional.empty(), Optional.of(taxId), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            true, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()
        );
    }

    public Customer withId(Long newId) {
        return new Customer(
            Optional.of(newId), tenantId, customerCode, customerType, name, tradeName,
            taxId, email, phone, addressStreet, addressCity, addressState, addressZip,
            addressCountry, active, createdAt, updatedAt, createdBy, updatedBy
        );
    }

    /**
     * Create a copy with a different tenantId (preserves all other fields).
     */
    public Customer withTenantId(String newTenantId) {
        if (newTenantId == null || newTenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        return new Customer(
            id, newTenantId, customerCode, customerType, name, tradeName,
            taxId, email, phone, addressStreet, addressCity, addressState, addressZip,
            addressCountry, active, createdAt, updatedAt, createdBy, updatedBy
        );
    }

    public Customer deactivate() {
        return new Customer(
            id, tenantId, customerCode, customerType, name, tradeName,
            taxId, email, phone, addressStreet, addressCity, addressState, addressZip,
            addressCountry, false, createdAt, Optional.of(LocalDateTime.now()), createdBy, updatedBy
        );
    }

    @Override
    public String toString() {
        return "Customer[" +
            "id=" + id +
            ", tenantId=" + tenantId +
            ", customerCode=" + customerCode +
            ", customerType=" + customerType +
            ", name=" + name +
            ", tradeName=" + tradeName +
            ", taxId=" + taxId.map(Customer::mask)
            + ", email=" + email.map(Customer::maskEmail)
            + ", phone=" + phone +
            ", active=" + active +
            "]";
    }

    private static String mask(String value) {
        if (value == null || value.length() <= 4) {
            return "****";
        }
        return "****" + value.substring(value.length() - 4);
    }

    private static String maskEmail(String value) {
        if (value == null || value.isBlank()) {
            return "****";
        }
        int atIndex = value.indexOf('@');
        if (atIndex <= 1) {
            return "****";
        }
        return value.charAt(0) + "***" + value.substring(atIndex);
    }
}
