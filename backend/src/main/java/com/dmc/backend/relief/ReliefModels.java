package com.dmc.backend.relief;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

public final class ReliefModels {
    private ReliefModels() { }
    public enum ShelterStatus { OPEN, FULL, CLOSED }
    public record History(String id, String type, String detail, String officer, Instant at) { }
    public record Allocation(String requestId, String shelterId, String shelterName, long quantity,
            String note, String officer, Instant at) { }
    @Document("relief_resources")
    public record Resource(@Id String id, @Version Long version, String name, String type,
            String location, long availableQuantity, String unit, String updatedBy, Instant updatedAt,
            List<Allocation> allocations, List<History> history) {
        public Resource { allocations=allocations==null ? List.of() : List.copyOf(allocations);
            history=history==null ? List.of() : List.copyOf(history); }
    }
    @Document("shelters")
    public record Shelter(@Id String id, @Version Long version, String name, String location,
            long capacity, long occupancy, ShelterStatus status, String updatedBy, Instant updatedAt,
            List<History> history) {
        public Shelter { history=history==null ? List.of() : List.copyOf(history); }
    }
}
