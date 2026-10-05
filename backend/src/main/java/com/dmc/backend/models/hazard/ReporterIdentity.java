package com.dmc.backend.models.hazard;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Objects;

/** Reporter type is resolved from the authenticated account by a future application service. */
public record ReporterIdentity(@Valid @NotNull UserReference user, @NotNull ReporterType type) {
    public ReporterIdentity {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(type, "type");
    }
}
