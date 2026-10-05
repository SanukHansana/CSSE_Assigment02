package com.dmc.backend.models.hazard;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Small internal guards shared by immutable values and the aggregate. */
final class ModelChecks {
    private ModelChecks() { }

    static String nonblank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    static Instant timestamp(Instant value, String field) {
        return Objects.requireNonNull(value, field).truncatedTo(ChronoUnit.MILLIS);
    }

    static void description(String value) {
        if (value != null && value.length() > 500) {
            throw new IllegalArgumentException("description must contain at most 500 characters");
        }
    }
}
