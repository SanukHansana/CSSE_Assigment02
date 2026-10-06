package com.dmc.backend.rescue;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

public final class RescueModels {
    private RescueModels() { }
    public enum IncidentType { FLOOD_RESCUE, MEDICAL_RESCUE, STRUCTURAL_RESCUE, OTHER }
    public enum Priority { LOW, MEDIUM, HIGH, CRITICAL }
    public enum IncidentStatus { OPEN, RESOLVED, CLOSED }
    public enum TeamStatus { AVAILABLE, UNAVAILABLE, DEPLOYED, BUSY }
    public record Event(String id, String detail, String officer, Instant at) { }
    @Document("rescue_incidents")
    public record Incident(@Id String id, @Version Long version, String reference, IncidentType type,
            Priority priority, String description, String location, double latitude, double longitude,
            long peopleNeedingAssistance, IncidentStatus status, String updatedBy, Instant updatedAt,
            List<Event> history) {
        public Incident { history=history==null ? List.of() : List.copyOf(history); }
    }
    @Document("rescue_teams")
    public record Team(@Id String id, @Version Long version, String name, IncidentType specialization,
            String location, double latitude, double longitude, TeamStatus status, String activeIncidentId,
            String updatedBy, Instant updatedAt) { }
}
