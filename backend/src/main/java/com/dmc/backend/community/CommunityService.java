package com.dmc.backend.community;

import com.dmc.backend.auth.*;
import com.dmc.backend.community.CommunityNeed.*;
import java.time.Clock;
import java.util.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CommunityService {

  private final MongoTemplate mongo;
  private final AuthService auth;
  private final Clock clock;

  public CommunityService(MongoTemplate mongo, AuthService auth, Clock clock) {
    this.mongo = mongo;
    this.auth = auth;
    this.clock = clock;
  }

  public record OfferView(
    String id,
    String volunteerName,
    long quantity,
    String note,
    OfferStatus status,
    boolean mine,
    java.time.Instant updatedAt
  ) {}

  public record NeedView(
    String id,
    Long version,
    String citizenName,
    String item,
    String category,
    long quantity,
    String unit,
    String description,
    String area,
    Status status,
    long received,
    long remainingToCommit,
    boolean mine,
    List<OfferView> offers,
    List<Event> history
  ) {}

  public record Page(
    List<NeedView> items,
    int page,
    int size,
    long totalElements,
    long totalPages
  ) {}

  public CommunityNeed create(
    String subject,
    CommunityController.CreateRequest body
  ) {
    UserAccount account = role(subject, AccountRole.CITIZEN);
    if (body.quantity() <= 0) throw fail(
      HttpStatus.BAD_REQUEST,
      "Quantity must be positive."
    );
    var at = clock.instant();
    return mongo.insert(
      new CommunityNeed(
        UUID.randomUUID().toString(),
        null,
        account.id(),
        account.displayName(),
        body.item().strip(),
        body.category().strip(),
        body.quantity(),
        body.unit().strip(),
        body.description().strip(),
        body.area().strip(),
        Status.OPEN,
        at,
        at,
        List.of(),
        List.of(
          new Event(
            UUID.randomUUID().toString(),
            "Need posted",
            account.displayName(),
            at
          )
        )
      )
    );
  }

  public Page list(String subject, String view, int page, int size) {
    UserAccount account = auth.requireActiveAccount(subject);
    if (page < 0 || size < 1 || size > 100) throw fail(
      HttpStatus.BAD_REQUEST,
      "Use page >= 0 and size 1–100."
    );
    Criteria filter;
    switch (view) {
      case "mine" -> {
        role(subject, AccountRole.CITIZEN);
        filter = Criteria.where("citizenId").is(account.id());
      }
      case "available" -> {
        role(subject, AccountRole.COMMUNITY_VOLUNTEER);
        filter = Criteria.where("status").is(Status.OPEN);
      }
      case "offers" -> {
        role(subject, AccountRole.COMMUNITY_VOLUNTEER);
        filter = Criteria.where("offers.volunteerId").is(account.id());
      }
      default -> throw fail(
        HttpStatus.BAD_REQUEST,
        "Choose mine, available or offers."
      );
    }
    long total = mongo.count(Query.query(filter), CommunityNeed.class);
    var query = Query.query(filter)
      .with(Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("_id")))
      .skip((long) page * size)
      .limit(size);
    return new Page(
      mongo
        .find(query, CommunityNeed.class)
        .stream()
        .map(need -> project(need, account.id()))
        .toList(),
      page,
      size,
      total,
      (total + size - 1) / size
    );
  }

  public NeedView detail(String subject, String id) {
    UserAccount account = auth.requireActiveAccount(subject);
    CommunityNeed need = find(id);
    boolean volunteer = account
      .effectiveRoles()
      .contains(AccountRole.COMMUNITY_VOLUNTEER);
    boolean participant = need
      .offers()
      .stream()
      .anyMatch(o -> o.volunteerId().equals(account.id()));
    if (
      !need.citizenId().equals(account.id()) &&
      !(volunteer && (need.status() == Status.OPEN || participant))
    ) throw fail(HttpStatus.NOT_FOUND, "Need not found.");
    return project(need, account.id());
  }

  public NeedView offer(
    String subject,
    String id,
    CommunityController.OfferRequest body
  ) {
    UserAccount account = role(subject, AccountRole.COMMUNITY_VOLUNTEER);
    CommunityNeed need = find(id);
    if (need.citizenId().equals(account.id())) throw fail(
      HttpStatus.FORBIDDEN,
      "You cannot offer to your own need."
    );
    String detail =
      "OFFER " +
      account.id() +
      " " +
      body.quantity() +
      ". " +
      body.note().strip();
    if (replay(need, body.requestId(), detail)) return project(
      need,
      account.id()
    );
    version(need, body.expectedVersion());
    open(need);
    if (
      body.quantity() <= 0 ||
      body.quantity() > need.quantity() - need.committed()
    ) throw fail(
      HttpStatus.CONFLICT,
      "Offer quantity exceeds the uncommitted need."
    );
    var offers = new ArrayList<>(need.offers());
    offers.add(
      new Offer(
        body.requestId(),
        account.id(),
        account.displayName(),
        body.quantity(),
        body.note().strip(),
        OfferStatus.OFFERED,
        clock.instant()
      )
    );
    return project(
      save(
        need,
        offers,
        need.status(),
        body.requestId(),
        detail,
        account.displayName()
      ),
      account.id()
    );
  }

  public NeedView act(
    String subject,
    String id,
    String offerId,
    CommunityController.ActionRequest body
  ) {
    UserAccount account = auth.requireActiveAccount(subject);
    CommunityNeed need = find(id);
    var offers = new ArrayList<>(need.offers());
    int index = -1;
    for (int i = 0; i < offers.size(); i++) if (
      offers.get(i).id().equals(offerId)
    ) index = i;
    if (index < 0) throw fail(HttpStatus.NOT_FOUND, "Offer not found.");
    Offer offer = offers.get(index);
    String action = body.action();
    boolean owner = need.citizenId().equals(account.id());
    if (List.of("ACCEPT", "DECLINE", "RECEIVE").contains(action)) {
      role(subject, AccountRole.CITIZEN);
      if (!owner) throw fail(
        HttpStatus.FORBIDDEN,
        "Only the requester can decide or confirm receipt."
      );
    } else if (List.of("DELIVER", "WITHDRAW").contains(action)) {
      role(subject, AccountRole.COMMUNITY_VOLUNTEER);
      if (!offer.volunteerId().equals(account.id())) throw fail(
        HttpStatus.FORBIDDEN,
        "Only the offering volunteer can update this offer."
      );
    } else throw fail(
      HttpStatus.BAD_REQUEST,
      "Choose ACCEPT, DECLINE, DELIVER, RECEIVE or WITHDRAW."
    );
    String detail = action + " " + offerId + " by " + account.id();
    if (replay(need, body.requestId(), detail)) return project(
      need,
      account.id()
    );
    version(need, body.expectedVersion());
    open(need);
    OfferStatus next;
    switch (action) {
      case "ACCEPT" -> {
        state(offer, OfferStatus.OFFERED);
        if (offer.quantity() > need.quantity() - need.committed()) throw fail(
          HttpStatus.CONFLICT,
          "Other accepted offers already cover this quantity."
        );
        next = OfferStatus.ACCEPTED;
      }
      case "DECLINE" -> {
        state(offer, OfferStatus.OFFERED);
        next = OfferStatus.DECLINED;
      }
      case "DELIVER" -> {
        state(offer, OfferStatus.ACCEPTED);
        next = OfferStatus.DELIVERED;
      }
      case "RECEIVE" -> {
        state(offer, OfferStatus.DELIVERED);
        next = OfferStatus.RECEIVED;
      }
      default -> {
        if (
          !List.of(OfferStatus.OFFERED, OfferStatus.ACCEPTED).contains(
            offer.status()
          )
        ) throw fail(HttpStatus.CONFLICT, "This offer cannot be withdrawn.");
        next = OfferStatus.WITHDRAWN;
      }
    }
    offers.set(
      index,
      new Offer(
        offer.id(),
        offer.volunteerId(),
        offer.volunteerName(),
        offer.quantity(),
        offer.note(),
        next,
        clock.instant()
      )
    );
    long received = offers
      .stream()
      .filter(o -> o.status() == OfferStatus.RECEIVED)
      .mapToLong(Offer::quantity)
      .sum();
    return project(
      save(
        need,
        offers,
        received == need.quantity() ? Status.FULFILLED : Status.OPEN,
        body.requestId(),
        detail,
        account.displayName()
      ),
      account.id()
    );
  }

  public NeedView cancel(
    String subject,
    String id,
    CommunityController.CancelRequest body
  ) {
    UserAccount account = role(subject, AccountRole.CITIZEN);
    CommunityNeed need = find(id);
    if (!need.citizenId().equals(account.id())) throw fail(
      HttpStatus.FORBIDDEN,
      "Only the requester can cancel."
    );
    String detail = "CANCEL by " + account.id();
    if (replay(need, body.requestId(), detail)) return project(
      need,
      account.id()
    );
    version(need, body.expectedVersion());
    open(need);
    if (
      need
        .offers()
        .stream()
        .anyMatch(o ->
          List.of(OfferStatus.ACCEPTED, OfferStatus.DELIVERED).contains(
            o.status()
          )
        )
    ) throw fail(
      HttpStatus.CONFLICT,
      "Resolve accepted deliveries before cancelling."
    );
    var offers = need
      .offers()
      .stream()
      .map(o ->
        o.status() == OfferStatus.OFFERED
          ? new Offer(
              o.id(),
              o.volunteerId(),
              o.volunteerName(),
              o.quantity(),
              o.note(),
              OfferStatus.DECLINED,
              clock.instant()
            )
          : o
      )
      .toList();
    return project(
      save(
        need,
        offers,
        Status.CANCELLED,
        body.requestId(),
        detail,
        account.displayName()
      ),
      account.id()
    );
  }

  public NeedView project(CommunityNeed need, String viewer) {
    boolean owner = need.citizenId().equals(viewer);
    var offers = need
      .offers()
      .stream()
      .filter(o -> owner || o.volunteerId().equals(viewer))
      .map(o ->
        new OfferView(
          o.id(),
          o.volunteerName(),
          o.quantity(),
          o.note(),
          o.status(),
          o.volunteerId().equals(viewer),
          o.updatedAt()
        )
      )
      .toList();
    // History includes internal replay fingerprints. Return human-readable events without account IDs.
    var events = need
      .history()
      .stream()
      .map(e ->
        new Event(
          e.requestId(),
          e.detail().startsWith("Need posted")
            ? "Need posted"
            : e.detail().split(" ")[0],
          e.actor(),
          e.at()
        )
      )
      .toList();
    return new NeedView(
      need.id(),
      need.version(),
      need.citizenName(),
      need.item(),
      need.category(),
      need.quantity(),
      need.unit(),
      need.description(),
      need.area(),
      need.status(),
      need.received(),
      need.quantity() - need.committed(),
      owner,
      offers,
      events
    );
  }

  private UserAccount role(String subject, AccountRole role) {
    UserAccount account = auth.requireActiveAccount(subject);
    if (!account.effectiveRoles().contains(role)) throw fail(
      HttpStatus.FORBIDDEN,
      "This action requires " + role + " access."
    );
    return account;
  }

  private CommunityNeed find(String id) {
    CommunityNeed need = mongo.findById(id, CommunityNeed.class);
    if (need == null) throw fail(HttpStatus.NOT_FOUND, "Need not found.");
    return need;
  }

  private void open(CommunityNeed need) {
    if (need.status() != Status.OPEN) throw fail(
      HttpStatus.CONFLICT,
      "Need is no longer open."
    );
  }

  private void version(CommunityNeed need, Long expected) {
    if (expected == null || !expected.equals(need.version())) throw fail(
      HttpStatus.CONFLICT,
      "Need changed. Reload before continuing."
    );
  }

  private void state(Offer offer, OfferStatus expected) {
    if (offer.status() != expected) throw fail(
      HttpStatus.CONFLICT,
      "Offer is not in the required state."
    );
  }

  private boolean replay(CommunityNeed need, String requestId, String detail) {
    Event event = need
      .history()
      .stream()
      .filter(e -> e.requestId().equals(requestId))
      .findFirst()
      .orElse(null);
    if (event == null) return false;
    if (!event.detail().equals(detail)) throw fail(
      HttpStatus.CONFLICT,
      "Request ID already used for different input."
    );
    return true;
  }

  private CommunityNeed save(
    CommunityNeed need,
    List<Offer> offers,
    Status status,
    String requestId,
    String detail,
    String actor
  ) {
    var at = clock.instant();
    var history = new ArrayList<>(need.history());
    history.add(new Event(requestId, detail, actor, at));
    return mongo.save(
      new CommunityNeed(
        need.id(),
        need.version(),
        need.citizenId(),
        need.citizenName(),
        need.item(),
        need.category(),
        need.quantity(),
        need.unit(),
        need.description(),
        need.area(),
        status,
        need.createdAt(),
        at,
        offers,
        history
      )
    );
  }

  private ResponseStatusException fail(HttpStatus status, String message) {
    return new ResponseStatusException(status, message);
  }
}
