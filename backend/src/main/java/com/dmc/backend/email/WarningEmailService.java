package com.dmc.backend.email;

import com.dmc.backend.auth.AuthService;
import com.dmc.backend.warnings.DisasterWarning;
import com.dmc.backend.warnings.DisasterWarning.*;
import java.time.Clock;
import java.util.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Persists a versioned send claim before contacting Brevo. Unknown/in-progress sends cannot be replayed. */
@Service
public class WarningEmailService {

  private final MongoTemplate mongo;
  private final AuthService auth;
  private final Clock clock;
  private final BrevoEmailClient email;

  public WarningEmailService(
    MongoTemplate mongo,
    AuthService auth,
    Clock clock,
    BrevoEmailClient email
  ) {
    this.mongo = mongo;
    this.auth = auth;
    this.clock = clock;
    this.email = email;
  }

  public DisasterWarning send(
    String subject,
    String id,
    String requestId,
    Long version,
    List<String> addresses
  ) {
    var current = mongo.findById(id, DisasterWarning.class);
    if (current == null) throw fail(HttpStatus.NOT_FOUND, "Warning not found.");
    var recipients = addresses
      .stream()
      .map(a -> a.strip().toLowerCase(Locale.ROOT))
      .distinct()
      .sorted()
      .toList();
    var previous = current.emailDispatch();
    if (previous != null && previous.requestId().equals(requestId)) {
      if (!previous.recipients().equals(recipients)) throw fail(
        HttpStatus.CONFLICT,
        "Request ID already used for different recipients."
      );
      return current;
    }
    if (version == null || !version.equals(current.version())) throw fail(
      HttpStatus.CONFLICT,
      "Warning changed. Reload before sending."
    );
    if (
      current.status() == Status.CANCELLED ||
      !current.validUntil().isAfter(clock.instant())
    ) throw fail(HttpStatus.CONFLICT, "Warning is cancelled or expired.");
    if (!current.channels().contains(Channel.EMAIL)) throw fail(
      HttpStatus.BAD_REQUEST,
      "Select Email in the warning draft."
    );
    if (previous != null && !previous.status().equals("FAILED")) throw fail(
      HttpStatus.CONFLICT,
      "Email already accepted or its outcome is uncertain. Inspect the saved status and Brevo logs."
    );
    if (!email.configured()) throw fail(
      HttpStatus.SERVICE_UNAVAILABLE,
      "Configure BREVO_API_KEY and BREVO_SENDER_EMAIL in the backend."
    );
    String officer = auth.requireActiveAccount(subject).displayName();
    int attempts =
      current
        .deliveries()
        .stream()
        .filter(d -> d.channel() == Channel.EMAIL)
        .mapToInt(Delivery::attempts)
        .max()
        .orElse(0) + 1;
    var dispatch = new EmailDispatch(
      requestId,
      recipients,
      "SENDING",
      null,
      "Send claim recorded.",
      clock.instant()
    );
    var claimed = mongo.save(copy(current, dispatch, attempts, officer, true));
    String text =
      "Warning: " +
      claimed.hazard() +
      " (" +
      claimed.level() +
      ")\nArea: " +
      claimed.affectedArea() +
      "\n\n" +
      claimed.message() +
      "\n\nSafety advice: " +
      claimed.instructions() +
      "\nValid until: " +
      claimed.validUntil() +
      "\nReference: " +
      claimed.reference();
    var result = email.send(
      recipients,
      "DMC " + claimed.level() + " warning: " + claimed.hazard(),
      text,
      "warning-" + id + "-" + requestId
    );
    var finished = new EmailDispatch(
      requestId,
      recipients,
      result.status(),
      result.messageId(),
      result.message(),
      dispatch.attemptedAt()
    );
    // If this save fails, SENDING stays persisted. Never automatically send that claim again.
    return mongo.save(copy(claimed, finished, attempts, officer, false));
  }

  private DisasterWarning copy(
    DisasterWarning warning,
    EmailDispatch dispatch,
    int attempts,
    String officer,
    boolean claim
  ) {
    var deliveries = new ArrayList<>(
      warning
        .deliveries()
        .stream()
        .filter(d -> d.channel() != Channel.EMAIL)
        .toList()
    );
    deliveries.add(
      new Delivery(
        Channel.EMAIL,
        dispatch.status(),
        attempts,
        dispatch.attemptedAt()
      )
    );
    var history = new ArrayList<>(warning.history());
    if (claim) history.add(
      new Event(
        dispatch.requestId(),
        "EMAIL_BROADCAST",
        "Email send claimed for " +
          dispatch.recipients().size() +
          " recipients",
        officer,
        clock.instant()
      )
    );
    return new DisasterWarning(
      warning.id(),
      warning.version(),
      warning.reference(),
      warning.hazard(),
      warning.level(),
      warning.affectedArea(),
      warning.message(),
      warning.instructions(),
      warning.channels(),
      warning.validUntil(),
      Status.ISSUED,
      warning.createdBy(),
      warning.createdAt(),
      officer,
      clock.instant(),
      deliveries,
      history,
      dispatch
    );
  }

  private ResponseStatusException fail(HttpStatus status, String message) {
    return new ResponseStatusException(status, message);
  }
}
