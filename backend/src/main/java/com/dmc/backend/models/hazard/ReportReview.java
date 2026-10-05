package com.dmc.backend.models.hazard;

import com.dmc.backend.models.hazard.HazardReport.UserReference;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Objects;

/** Editable review working data; the final verification holds a separate immutable snapshot. */
public record ReportReview(@Valid @NotNull UserReference officer, Instant startedAt,
                          @NotNull CredibilityChecklist checklist, String comments) {
    public ReportReview {
        Objects.requireNonNull(officer, "officer");
        startedAt = HazardReport.timestamp(startedAt, "startedAt");
        Objects.requireNonNull(checklist, "checklist");
    }
}
