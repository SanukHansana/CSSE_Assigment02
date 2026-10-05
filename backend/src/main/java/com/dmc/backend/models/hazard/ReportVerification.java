package com.dmc.backend.models.hazard;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Objects;

/** Embedded decision record: persist this, final status, and history in one document write. */
public record ReportVerification(
        String reference, @Valid @NotNull UserReference officer,
        @NotNull VerificationDecision decision, String comments, String rejectionReason,
        @NotNull CredibilityChecklist checklist, Instant decidedAt) {
    public ReportVerification {
        ModelChecks.nonblank(reference, "verification reference");
        Objects.requireNonNull(officer, "officer");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(checklist, "checklist");
        decidedAt = ModelChecks.timestamp(decidedAt, "decidedAt");
        if (decision == VerificationDecision.REJECTED) {
            ModelChecks.nonblank(rejectionReason, "rejectionReason");
        } else {
            if (!checklist.allPassed()) {
                throw new IllegalArgumentException("verification requires all four credibility checks to pass");
            }
            if (rejectionReason != null) {
                throw new IllegalArgumentException("a verified report cannot have a rejectionReason");
            }
        }
    }
}
