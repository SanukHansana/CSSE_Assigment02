package com.dmc.backend.community;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/** Personal volunteer support stays separate from official DMC resource inventory. */
@Document("community_needs")
public record CommunityNeed(
  @Id String id,
  @Version Long version,
  String citizenId,
  String citizenName,
  String item,
  String category,
  long quantity,
  String unit,
  String description,
  String area,
  Status status,
  Instant createdAt,
  Instant updatedAt,
  List<Offer> offers,
  List<Event> history
) {
  public enum Status {
    OPEN,
    FULFILLED,
    CANCELLED,
  }

  public enum OfferStatus {
    OFFERED,
    ACCEPTED,
    DELIVERED,
    RECEIVED,
    DECLINED,
    WITHDRAWN,
  }

  public record Offer(
    String id,
    String volunteerId,
    String volunteerName,
    long quantity,
    String note,
    OfferStatus status,
    Instant updatedAt
  ) {}

  public record Event(
    String requestId,
    String detail,
    String actor,
    Instant at
  ) {}

  public CommunityNeed {
    offers = offers == null ? List.of() : List.copyOf(offers);
    history = history == null ? List.of() : List.copyOf(history);
  }

  public long received() {
    return offers
      .stream()
      .filter(o -> o.status() == OfferStatus.RECEIVED)
      .mapToLong(Offer::quantity)
      .sum();
  }

  public long committed() {
    return offers
      .stream()
      .filter(o ->
        List.of(
          OfferStatus.ACCEPTED,
          OfferStatus.DELIVERED,
          OfferStatus.RECEIVED
        ).contains(o.status())
      )
      .mapToLong(Offer::quantity)
      .sum();
  }
}
