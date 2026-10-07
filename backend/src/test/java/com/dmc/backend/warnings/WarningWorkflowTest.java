package com.dmc.backend.warnings;

import static org.assertj.core.api.Assertions.*;

import com.dmc.backend.support.WorkflowFixtures;
import com.dmc.backend.warnings.DisasterWarning.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class WarningWorkflowTest {

  WorkflowFixtures fixture;
  WarningController controller;
  DisasterWarning warning;

  @BeforeEach
  void setup() {
    fixture = new WorkflowFixtures();
    controller = new WarningController(
      fixture.mongo,
      fixture.auth,
      fixture.clock
    );
    warning = controller.create(fixture.jwt("officer"), draft(null));
  }

  WarningController.DraftRequest draft(Long version) {
    return new WarningController.DraftRequest(
      "Flood",
      Level.HIGH,
      "Colombo",
      "River rising",
      "Move to safety",
      Set.of(Channel.SMS, Channel.PUSH_NOTIFICATION),
      fixture.clock.instant().plusSeconds(3600),
      version
    );
  }

  void failure(Runnable work, HttpStatus status) {
    assertThatThrownBy(work::run).isInstanceOfSatisfying(
      ResponseStatusException.class,
      error -> assertThat(error.getStatusCode()).isEqualTo(status)
    );
  }

  @Test
  void draftPreviewAndEditsRetainCreatorAndUseNewVersion() {
    assertThat(warning.status()).isEqualTo(Status.DRAFT);
    assertThat(controller.detail(warning.id()).createdBy()).isEqualTo(
      "Test officer"
    );
    var updated = controller.update(
      fixture.jwt("officer"),
      warning.id(),
      draft(0L)
    );
    assertThat(updated.version()).isEqualTo(1);
    assertThat(updated.createdAt()).isEqualTo(warning.createdAt());
    assertThat(controller.list(0, 10).items()).hasSize(1);
    failure(
      () -> controller.update(fixture.jwt("officer"), warning.id(), draft(0L)),
      HttpStatus.CONFLICT
    );
    failure(
      () ->
        controller.update(fixture.jwt("officer"), warning.id(), draft(null)),
      HttpStatus.BAD_REQUEST
    );
  }

  @Test
  void simulatedBroadcastAndFailedOnlyRetryDoNotClaimRealRecipients() {
    var issued = controller.broadcast(
      fixture.jwt("officer"),
      warning.id(),
      new WarningController.BroadcastRequest("broadcast", 0L, Channel.SMS)
    );
    assertThat(issued.status()).isEqualTo(Status.ISSUED);
    assertThat(issued.deliveries())
      .filteredOn(d -> d.channel() == Channel.SMS)
      .singleElement()
      .satisfies(d -> assertThat(d.status()).isEqualTo("FAILED"));
    var retried = controller.retry(
      fixture.jwt("officer"),
      warning.id(),
      new WarningController.BroadcastRequest("retry", issued.version(), null)
    );
    assertThat(retried.deliveries()).allMatch(d ->
      d.status().equals("SIMULATED_SENT")
    );
    assertThat(retried.deliveries())
      .filteredOn(d -> d.channel() == Channel.PUSH_NOTIFICATION)
      .singleElement()
      .satisfies(d -> assertThat(d.attempts()).isEqualTo(1));
    assertThat(retried.deliveries())
      .filteredOn(d -> d.channel() == Channel.SMS)
      .singleElement()
      .satisfies(d -> assertThat(d.attempts()).isEqualTo(2));
    assertThat(retried.history()).hasSize(2);
    assertThat(
      controller
        .retry(
          fixture.jwt("officer"),
          warning.id(),
          new WarningController.BroadcastRequest("retry", 0L, null)
        )
        .version()
    ).isEqualTo(retried.version());
    failure(
      () ->
        controller.retry(
          fixture.jwt("officer"),
          warning.id(),
          new WarningController.BroadcastRequest(
            "again",
            retried.version(),
            null
          )
        ),
      HttpStatus.CONFLICT
    );
  }

  @Test
  void duplicateBroadcastAndCancellationDoNotRepeatEvents() {
    var body = new WarningController.BroadcastRequest("broadcast", 0L, null);
    var issued = controller.broadcast(
      fixture.jwt("officer"),
      warning.id(),
      body
    );
    assertThat(
      controller.broadcast(fixture.jwt("officer"), warning.id(), body).history()
    ).hasSize(1);
    var cancel = new WarningController.CancelRequest(
      "cancel",
      issued.version(),
      "Conditions improved"
    );
    var cancelled = controller.cancel(
      fixture.jwt("officer"),
      warning.id(),
      cancel
    );
    assertThat(cancelled.status()).isEqualTo(Status.CANCELLED);
    assertThat(
      controller.cancel(fixture.jwt("officer"), warning.id(), cancel).version()
    ).isEqualTo(cancelled.version());
    failure(
      () ->
        controller.broadcast(
          fixture.jwt("officer"),
          warning.id(),
          new WarningController.BroadcastRequest(
            "other",
            cancelled.version(),
            null
          )
        ),
      HttpStatus.CONFLICT
    );
    failure(
      () ->
        controller.cancel(
          fixture.jwt("officer"),
          warning.id(),
          new WarningController.CancelRequest(
            "cancel",
            cancelled.version(),
            "Different reason"
          )
        ),
      HttpStatus.CONFLICT
    );
  }

  @Test
  void changedReplayInputInvalidChannelAndTerminalEditsAreRejected() {
    failure(
      () ->
        controller.broadcast(
          fixture.jwt("officer"),
          warning.id(),
          new WarningController.BroadcastRequest(
            "bad",
            0L,
            Channel.AUDIBLE_ALERT
          )
        ),
      HttpStatus.BAD_REQUEST
    );
    var issued = controller.broadcast(
      fixture.jwt("officer"),
      warning.id(),
      new WarningController.BroadcastRequest("sent", 0L, null)
    );
    failure(
      () ->
        controller.broadcast(
          fixture.jwt("officer"),
          warning.id(),
          new WarningController.BroadcastRequest("sent", 0L, Channel.SMS)
        ),
      HttpStatus.CONFLICT
    );
    failure(
      () ->
        controller.update(
          fixture.jwt("officer"),
          warning.id(),
          draft(issued.version())
        ),
      HttpStatus.CONFLICT
    );
    failure(
      () ->
        controller.broadcast(
          fixture.jwt("officer"),
          warning.id(),
          new WarningController.BroadcastRequest("stale", 0L, null)
        ),
      HttpStatus.CONFLICT
    );
  }

  @Test
  void expiredDraftMissingRecordsAndInvalidPagesFail() {
    var expired = new WarningController.DraftRequest(
      "Flood",
      Level.HIGH,
      "Town",
      "Message",
      "Advice",
      Set.of(Channel.SMS),
      fixture.clock.instant(),
      null
    );
    failure(
      () -> controller.create(fixture.jwt("officer"), expired),
      HttpStatus.BAD_REQUEST
    );
    var futureController = new WarningController(
      fixture.mongo,
      fixture.auth,
      java.time.Clock.offset(fixture.clock, java.time.Duration.ofHours(2))
    );
    failure(
      () ->
        futureController.broadcast(
          fixture.jwt("officer"),
          warning.id(),
          new WarningController.BroadcastRequest("expired", 0L, null)
        ),
      HttpStatus.CONFLICT
    );
    failure(
      () ->
        controller.cancel(
          fixture.jwt("officer"),
          warning.id(),
          new WarningController.CancelRequest("bad", 0L, "Draft")
        ),
      HttpStatus.CONFLICT
    );
    failure(
      () ->
        controller.retry(
          fixture.jwt("officer"),
          warning.id(),
          new WarningController.BroadcastRequest("bad", 0L, null)
        ),
      HttpStatus.CONFLICT
    );
    failure(() -> controller.detail("missing"), HttpStatus.NOT_FOUND);
    failure(() -> controller.list(-1, 10), HttpStatus.BAD_REQUEST);
    failure(() -> controller.list(0, 0), HttpStatus.BAD_REQUEST);
    failure(() -> controller.list(0, 101), HttpStatus.BAD_REQUEST);
  }
}
