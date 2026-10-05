package com.orthoflow.inventory.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Request DTO for creating/updating a supplier. Deliberately excludes `id`,
 * `createdAt`, `updatedAt` — those are server-owned. Binding the entity
 * directly (the previous behaviour) let a client set those on a POST/PUT
 * with zero validation (a blank name, a malformed email) silently succeeding
 * (see audit I.5 / V.6).
 */
@Getter
@Setter
public class SupplierRequest {

    @NotBlank
    private String name;

    private String contactName;

    @Email
    private String email;

    private String phone;

    private String address;

    private boolean active = true;

    /** SUPPLIER (default) or LAB. */
    @jakarta.validation.constraints.Pattern(regexp = "SUPPLIER|LAB", message = "kind must be SUPPLIER or LAB")
    private String kind;
}
