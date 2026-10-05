package com.dmc.backend.models.hazard;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Objects;

/** Server event time only. Previous state is null for the initial draft creation event. */
public record ReportHistoryEvent(String id, @NotNull HistoryEventType type,
        ReportStatus previousStatus, @NotNull ReportStatus status,
        @Valid @NotNull UserReference actor, Instant occurredAt) {
    public ReportHistoryEvent {
        ModelChecks.nonblank(id, "history id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(actor, "actor");
        occurredAt = ModelChecks.timestamp(occurredAt, "occurredAt");
    }
}
