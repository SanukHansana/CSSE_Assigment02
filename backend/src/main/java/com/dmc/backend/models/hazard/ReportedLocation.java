package com.dmc.backend.models.hazard;

import java.time.Instant;

/** WGS84 coordinates. Capture time is client-originated and separate from server receipt. */
public record ReportedLocation(double latitude, double longitude, String areaLabel, Instant capturedAt) {
    public ReportedLocation {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("latitude must be finite and between -90 and 90");
        }
        if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("longitude must be finite and between -180 and 180");
        }
        if (capturedAt != null) {
            capturedAt = ModelChecks.timestamp(capturedAt, "capturedAt");
        }
    }
}
