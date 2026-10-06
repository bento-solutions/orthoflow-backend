package com.orthoflow.team.application.dto;

import com.orthoflow.team.domain.model.Practitioner;
import com.orthoflow.team.domain.model.Specialty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public final class PractitionerDtos {

    private PractitionerDtos() {
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "PractitionerRequest")

    public record Request(
            @NotBlank @Size(max = 150) String displayName,
            UUID userId,
            @Pattern(regexp = "^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$", message = "color must be a hex colour such as #2563eb") String color,
            @NotNull Specialty specialty,
            @Size(max = 20) String inpe,
            Boolean active) {
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "PractitionerResponse")

    public record Response(UUID id, UUID userId, String displayName, String color, Specialty specialty,
                           String inpe, int displayOrder, boolean active) {
        public static Response from(Practitioner p) {
            return new Response(p.getId(), p.getUserId(), p.getDisplayName(), p.getColor(), p.getSpecialty(),
                    p.getInpe(), p.getDisplayOrder(), p.isActive());
        }
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "PractitionerReorder")

    public record Reorder(@NotEmpty List<UUID> orderedIds) {
    }

    /** A free-text doctor name from before practitioners existed that matched no one. */
    public record Unmatched(String doctorName, int rowCount) {
    }

    public record ResolveUnmatched(@NotBlank String doctorName, @NotNull UUID practitionerId) {
    }
}
