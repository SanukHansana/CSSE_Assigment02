package com.dmc.backend.models.hazard;

/** Null means not assessed; false means assessed and failed. Matches the four review checkboxes. */
public record CredibilityChecklist(
        Boolean descriptionSufficientlyDetailed, Boolean photoRelevant,
        Boolean gpsCorrespondsToArea, Boolean reportingTimeReasonable) {
    public static CredibilityChecklist unassessed() {
        return new CredibilityChecklist(null, null, null, null);
    }

    /** Proposed verification policy: all four pass; rejection never requires them to pass. */
    public boolean allPassed() {
        return Boolean.TRUE.equals(descriptionSufficientlyDetailed)
                && Boolean.TRUE.equals(photoRelevant)
                && Boolean.TRUE.equals(gpsCorrespondsToArea)
                && Boolean.TRUE.equals(reportingTimeReasonable);
    }
}
