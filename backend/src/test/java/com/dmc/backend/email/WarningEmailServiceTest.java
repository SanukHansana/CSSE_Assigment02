package com.dmc.backend.email;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dmc.backend.support.WorkflowFixtures;
import com.dmc.backend.warnings.*;
import com.dmc.backend.warnings.DisasterWarning.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.*;
import org.springframework.web.server.ResponseStatusException;

class WarningEmailServiceTest {

  WorkflowFixtures f;
  BrevoEmailClient email;
  WarningEmailService service;
  WarningController controller;
  DisasterWarning warning;

  @BeforeEach
  void setup() {
    f = new WorkflowFixtures();
    email = mock(BrevoEmailClient.class);
    when(email.configured()).thenReturn(true);
    when(
      email.send(anyList(), anyString(), anyString(), anyString())
    ).thenReturn(
      new BrevoEmailClient.Result(
        "ACCEPTED",
        "provider-reference",
        "Accepted, not delivered"
      )
    );
    service = new WarningEmailService(f.mongo, f.auth, f.clock, email);
    controller = new WarningController(f.mongo, f.auth, f.clock, service);
    warning = controller.create(f.jwt("officer"), draft(Set.of(Channel.EMAIL)));
  }

  WarningController.DraftRequest draft(Set<Channel> channels) {
    return new WarningController.DraftRequest(
      "Flood",
      Level.HIGH,
      "Colombo",
      "Rising water",
      "Move higher",
      channels,
      f.clock.instant().plusSeconds(3600),
      null
    );
  }

  DisasterWarning send(String id, Long version) {
    return service.send(
      "officer",
      warning.id(),
      id,
      version,
      List.of("Recipient@example.test", "recipient@example.test")
    );
  }

  void fail(int status, Runnable work) {
    assertThatThrownBy(work::run).isInstanceOfSatisfying(
      ResponseStatusException.class,
      e -> assertThat(e.getStatusCode().value()).isEqualTo(status)
    );
  }

  @Test
  void sendClaimPrecedesProviderCallAndDuplicateIdCannotResend() {
    doAnswer(call -> {
      assertThat(
        f.get(DisasterWarning.class, warning.id()).emailDispatch().status()
      ).isEqualTo("SENDING");
      return new BrevoEmailClient.Result(
        "ACCEPTED",
        "provider-reference",
        "Accepted"
      );
    })
      .when(email)
      .send(anyList(), anyString(), anyString(), anyString());
    var saved = controller.sendEmail(
      f.jwt("officer"),
      warning.id(),
      new WarningController.EmailRequest(
        "send",
        0L,
        List.of("Recipient@example.test", "recipient@example.test")
      )
    );
    assertThat(saved.status()).isEqualTo(Status.ISSUED);
    assertThat(saved.version()).isEqualTo(2L);
    assertThat(saved.emailDispatch().recipients()).containsExactly(
      "recipient@example.test"
    );
    assertThat(saved.emailDispatch().providerMessageId()).isEqualTo(
      "provider-reference"
    );
    assertThat(send("send", 0L)).isEqualTo(saved);
    verify(email, times(1)).send(
      anyList(),
      anyString(),
      anyString(),
      anyString()
    );
    fail(409, () -> send("new", saved.version()));
    fail(409, () ->
      service.send(
        "officer",
        warning.id(),
        "send",
        saved.version(),
        List.of("other@example.test")
      )
    );
  }

  @Test
  void failedKnownRejectionCanBeRetriedWithNewIdButUnknownCannot() {
    when(
      email.send(anyList(), anyString(), anyString(), anyString())
    ).thenReturn(new BrevoEmailClient.Result("FAILED", null, "Rejected"));
    var failed = send("send", 0L);
    assertThat(failed.emailDispatch().status()).isEqualTo("FAILED");
    when(
      email.send(anyList(), anyString(), anyString(), anyString())
    ).thenReturn(
      new BrevoEmailClient.Result("UNKNOWN", null, "Check provider")
    );
    var uncertain = send("retry", failed.version());
    assertThat(uncertain.deliveries().getFirst().attempts()).isEqualTo(2);
    fail(409, () -> send("again", uncertain.version()));
    verify(email, times(2)).send(
      anyList(),
      anyString(),
      anyString(),
      anyString()
    );
  }

  @Test
  void missingConfigurationVersionMissingRecordExpiredAndWrongChannelAreRejected() {
    fail(404, () ->
      service.send("officer", "missing", "send", 0L, List.of("a@example.test"))
    );
    fail(409, () -> send("send", null));
    fail(409, () -> send("send", 8L));
    when(email.configured()).thenReturn(false);
    fail(503, () -> send("send", 0L));
    verify(email, never()).send(
      anyList(),
      anyString(),
      anyString(),
      anyString()
    );
    warning = controller.create(f.jwt("officer"), draft(Set.of(Channel.SMS)));
    fail(400, () -> send("send", 0L));
  }

  @Test
  void failedClaimNeverContactsProvider() {
    doThrow(new OptimisticLockingFailureException("Changed"))
      .when(f.mongo)
      .save(any(DisasterWarning.class));
    assertThatThrownBy(() -> send("send", 0L)).isInstanceOf(
      OptimisticLockingFailureException.class
    );
    verify(email, never()).send(
      anyList(),
      anyString(),
      anyString(),
      anyString()
    );
  }

  @Test
  void failedAcknowledgementSaveLeavesClaimAndReplayNeverResends() {
    doAnswer(call -> {
      doThrow(new DataAccessResourceFailureException("Offline"))
        .when(f.mongo)
        .save(any(DisasterWarning.class));
      return new BrevoEmailClient.Result(
        "ACCEPTED",
        "provider-reference",
        "Accepted"
      );
    })
      .when(email)
      .send(anyList(), anyString(), anyString(), anyString());
    assertThatThrownBy(() -> send("send", 0L)).isInstanceOf(
      DataAccessResourceFailureException.class
    );
    assertThat(
      f.get(DisasterWarning.class, warning.id()).emailDispatch().status()
    ).isEqualTo("SENDING");
    assertThat(send("send", 0L).emailDispatch().status()).isEqualTo("SENDING");
    fail(409, () -> send("again", 1L));
    fail(409, () ->
      controller.cancel(
        f.jwt("officer"),
        warning.id(),
        new WarningController.CancelRequest("cancel", 1L, "Cancel")
      )
    );
    fail(409, () ->
      controller.retry(
        f.jwt("officer"),
        warning.id(),
        new WarningController.BroadcastRequest("r", 1L, null)
      )
    );
    verify(email, times(1)).send(
      anyList(),
      anyString(),
      anyString(),
      anyString()
    );
  }

  @Test
  void legacySimulationMarksEmailReadyAndPreservesAcceptedEmailOnCancellation() {
    var simulated = controller.broadcast(
      f.jwt("officer"),
      warning.id(),
      new WarningController.BroadcastRequest("simulate", 0L, null)
    );
    assertThat(simulated.deliveries().getFirst().status()).isEqualTo("READY");
    var saved = send("send", simulated.version());
    var cancelled = controller.cancel(
      f.jwt("officer"),
      warning.id(),
      new WarningController.CancelRequest("cancel", saved.version(), "Ended")
    );
    assertThat(cancelled.emailDispatch().status()).isEqualTo("ACCEPTED");
    fail(409, () -> send("new", cancelled.version()));
  }
}
