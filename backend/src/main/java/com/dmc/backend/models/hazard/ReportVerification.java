package com.dmc.backend.models.hazard;

import com.dmc.backend.models.hazard.HazardReport.UserReference;
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
        HazardReport.nonblank(reference, "verification reference");
        Objects.requireNonNull(officer, "officer");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(checklist, "checklist");
        decidedAt = HazardReport.timestamp(decidedAt, "decidedAt");
        if (decision == VerificationDecision.REJECTED) {
            HazardReport.nonblank(rejectionReason, "rejectionReason");
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
