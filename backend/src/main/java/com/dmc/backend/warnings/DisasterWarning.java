package com.dmc.backend.warnings;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("disaster_warnings")
public record DisasterWarning(@Id String id, @Version Long version, String reference,
        String hazard, Level level, String affectedArea, String message, String instructions,
        Set<Channel> channels, Instant validUntil, Status status, String createdBy,
        Instant createdAt, String updatedBy, Instant updatedAt, List<Delivery> deliveries, List<Event> history) {
    public enum Level { LOW, MEDIUM, HIGH }
    public enum Channel { PUSH_NOTIFICATION, SMS, AUDIBLE_ALERT }
    public enum Status { DRAFT, ISSUED, CANCELLED }
    public record Delivery(Channel channel, String status, int attempts, Instant lastAttemptAt) { }
    public record Event(String requestId, String action, String detail, String officer, Instant at) { }
    public DisasterWarning { deliveries=deliveries==null ? List.of() : List.copyOf(deliveries);
        history=history==null ? List.of() : List.copyOf(history); channels=channels==null ? Set.of() : Set.copyOf(channels); }
}
