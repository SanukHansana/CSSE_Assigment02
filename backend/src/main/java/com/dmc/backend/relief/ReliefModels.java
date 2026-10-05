package com.dmc.backend.relief;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/** Small versioned documents for the coursework relief coordination module. */
public final class ReliefModels {
    private ReliefModels() { }
    public enum ShelterStatus { OPEN, FULL, CLOSED }
    @Document("relief_resources")
    public record Resource(@Id String id, @Version Long version, String name, String type,
            String location, long availableQuantity, String unit, String updatedBy, Instant updatedAt) { }
    @Document("shelters")
    public record Shelter(@Id String id, @Version Long version, String name, String location,
            long capacity, long occupancy, ShelterStatus status, String updatedBy, Instant updatedAt) { }
}
