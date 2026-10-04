package com.dmc.backend.warnings;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("disaster_warnings")
public record DisasterWarning(
  @Id String id,
  @Version Long version,
  String reference,
  String hazard,
  Level level,
  String affectedArea,
  String message,
  String instructions,
  Set<Channel> channels,
  Instant validUntil,
  Status status,
  String createdBy,
  Instant createdAt,
  String updatedBy,
  Instant updatedAt,
  List<Delivery> deliveries,
  List<Event> history,
  EmailDispatch emailDispatch
) {
  public DisasterWarning(
    String id,
    Long version,
    String reference,
    String hazard,
    Level level,
    String affectedArea,
    String message,
    String instructions,
    Set<Channel> channels,
    Instant validUntil,
    Status status,
    String createdBy,
    Instant createdAt,
    String updatedBy,
    Instant updatedAt,
    List<Delivery> deliveries,
    List<Event> history
  ) {
    this(
      id,
      version,
      reference,
      hazard,
      level,
      affectedArea,
      message,
      instructions,
      channels,
      validUntil,
      status,
      createdBy,
      createdAt,
      updatedBy,
      updatedAt,
      deliveries,
      history,
      null
    );
  }

  public record EmailDispatch(
    String requestId,
    List<String> recipients,
    String status,
    String providerMessageId,
    String message,
    Instant attemptedAt
  ) {
    public EmailDispatch {
      recipients = List.copyOf(recipients);
    }
  }

  public enum Level {
    LOW,
    MEDIUM,
    HIGH,
  }

  public enum Channel {
    PUSH_NOTIFICATION,
    SMS,
    AUDIBLE_ALERT,
    EMAIL,
  }

  public enum Status {
    DRAFT,
    ISSUED,
    CANCELLED,
  }

  public record Delivery(
    Channel channel,
    String status,
    int attempts,
    Instant lastAttemptAt
  ) {}

  public record Event(
    String requestId,
    String action,
    String detail,
    String officer,
    Instant at
  ) {}

  public DisasterWarning {
    deliveries = deliveries == null ? List.of() : List.copyOf(deliveries);
    history = history == null ? List.of() : List.copyOf(history);
    channels = channels == null ? Set.of() : Set.copyOf(channels);
  }
}
