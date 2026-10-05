package com.dmc.backend.models.hazard;

import jakarta.validation.constraints.NotBlank;

/**
 * Opaque reference plus display-name snapshot, to be populated from trusted authentication.
 * This is not a user account, authentication module, or caller-supplied authorization claim.
 */
public record UserReference(@NotBlank String subjectId, @NotBlank String displayName) {
    public UserReference {
        ModelChecks.nonblank(subjectId, "subjectId");
        ModelChecks.nonblank(displayName, "displayName");
    }
}
