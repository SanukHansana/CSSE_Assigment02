package com.dmc.backend.models.hazard;

/** QUEUED_FOR_SYNC belongs to the client and is deliberately not a server status. */
public enum ReportStatus {
    DRAFT, SUBMITTED, UNDER_REVIEW, VERIFIED, REJECTED;

    public boolean canTransitionTo(ReportStatus next) {
        return switch (this) {
            case DRAFT -> next == SUBMITTED;
            case SUBMITTED -> next == UNDER_REVIEW;
            case UNDER_REVIEW -> next == VERIFIED || next == REJECTED;
            case VERIFIED, REJECTED -> false;
        };
    }
}
