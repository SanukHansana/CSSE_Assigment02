package com.dmc.backend.reporting;

import com.dmc.backend.models.hazard.CredibilityChecklist;
import com.dmc.backend.models.hazard.VerificationDecision;
import jakarta.validation.constraints.*;

public final class ReviewContracts {
    private ReviewContracts() { }
    public record UpdateReviewRequest(@NotNull @PositiveOrZero Long expectedVersion,
            @NotNull CredibilityChecklist checklist, @Size(max = 2000) String comments) { }
    /** Optional working data may be supplied with a decision, persisted in the same document write. */
    public record DecisionRequest(@NotNull @PositiveOrZero Long expectedVersion,
            @NotNull VerificationDecision decision, CredibilityChecklist checklist,
            @Size(max = 2000) String comments, @Size(max = 2000) String rejectionReason) { }
}
