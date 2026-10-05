package com.orthoflow.inventory.application.dto;

import com.orthoflow.inventory.domain.model.Supplier;

import java.util.UUID;

public record SupplierResponse(
        UUID id,
        String name,
        String contactName,
        String email,
        String phone,
        String address,
        String kind
) {
    public static SupplierResponse from(Supplier s) {
        if (s == null) return null;
        return new SupplierResponse(s.getId(), s.getName(), s.getContactName(), s.getEmail(), s.getPhone(), s.getAddress(),
                s.getKind() == null ? "SUPPLIER" : s.getKind().name());
    }
}
