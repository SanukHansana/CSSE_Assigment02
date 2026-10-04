package com.dmc.backend.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dmc.backend.email.BrevoEmailClient;
import com.dmc.backend.support.WorkflowFixtures;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.*;

class ApprovalEmailTest {

  @ParameterizedTest
  @ValueSource(strings = { "ACCEPTED", "FAILED", "UNKNOWN", "NOT_CONFIGURED" })
  void approvalIsRecordedEvenWhenNotificationFails(String status) {
    var f = new WorkflowFixtures();
    f.account("admin", AccountRole.ADMIN);
    var current = new UserAccount(
      "officer",
      "officer@example.test",
      "Officer",
      "hash",
      Set.of(AccountRole.DMC_OFFICER),
      true,
      f.clock.instant(),
      AccountRole.DMC_OFFICER,
      "PENDING",
      null,
      null,
      null
    );
    f.put(current);
    var approved = new UserAccount(
      current.id(),
      current.email(),
      current.displayName(),
      "hash",
      current.roles(),
      true,
      current.createdAt(),
      current.requestedRole(),
      "APPROVED",
      "Admin",
      f.clock.instant(),
      "Verified"
    );
    doReturn(approved)
      .when(f.mongo)
      .findAndModify(
        any(Query.class),
        any(Update.class),
        any(FindAndModifyOptions.class),
        eq(UserAccount.class)
      );
    var email = mock(BrevoEmailClient.class);
    when(
      email.send(anyList(), anyString(), anyString(), anyString())
    ).thenReturn(new BrevoEmailClient.Result(status, null, "Result"));
    var controller = new AdminController(f.mongo, f.auth, f.clock, email);
    var response = controller.decide(
      f.jwt("admin"),
      current.id(),
      new AdminController.Decision(true, "Verified")
    );
    assertThat(response.getBody().approvalStatus()).isEqualTo("APPROVED");
    assertThat(response.getBody().roles()).containsExactly(
      AccountRole.DMC_OFFICER
    );
    assertThat(response.getBody().emailNotificationStatus()).isEqualTo(status);
    verify(email).send(
      eq(List.of("officer@example.test")),
      eq("DMC officer application approved"),
      contains("Verified"),
      contains("approval-officer")
    );
  }

  @Test
  void rejectionUsesSameNotificationPathAndConcurrentReviewDoesNotEmail() {
    var f = new WorkflowFixtures();
    f.account("admin", AccountRole.ADMIN);
    var current = new UserAccount(
      "officer",
      "officer@example.test",
      "Officer",
      "hash",
      Set.of(AccountRole.DUTY_OFFICER),
      true,
      f.clock.instant(),
      AccountRole.DUTY_OFFICER,
      "PENDING",
      null,
      null,
      null
    );
    f.put(current);
    var email = mock(BrevoEmailClient.class);
    var controller = new AdminController(f.mongo, f.auth, f.clock, email);
    assertThatThrownBy(() ->
      controller.decide(
        f.jwt("admin"),
        current.id(),
        new AdminController.Decision(false, "Identity not verified")
      )
    ).isInstanceOf(AuthException.class);
    verifyNoInteractions(email);
    var rejected = new UserAccount(
      current.id(),
      current.email(),
      current.displayName(),
      "hash",
      current.roles(),
      true,
      current.createdAt(),
      current.requestedRole(),
      "REJECTED",
      "Admin",
      f.clock.instant(),
      "Identity not verified"
    );
    doReturn(rejected)
      .when(f.mongo)
      .findAndModify(
        any(Query.class),
        any(Update.class),
        any(FindAndModifyOptions.class),
        eq(UserAccount.class)
      );
    when(
      email.send(anyList(), anyString(), anyString(), anyString())
    ).thenReturn(new BrevoEmailClient.Result("ACCEPTED", "id", "Accepted"));
    var response = controller.decide(
      f.jwt("admin"),
      current.id(),
      new AdminController.Decision(false, "Identity not verified")
    );
    assertThat(response.getBody().roles()).isEmpty();
    verify(email).send(
      eq(List.of(current.email())),
      eq("DMC officer application rejected"),
      contains("Identity not verified"),
      anyString()
    );
  }
}
