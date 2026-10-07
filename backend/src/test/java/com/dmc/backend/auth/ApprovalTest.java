package com.dmc.backend.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dmc.backend.support.WorkflowFixtures;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.*;

class ApprovalTest {

  WorkflowFixtures f;
  AdminController controller;
  UserAccount pending;

  @BeforeEach
  void setup() {
    f = new WorkflowFixtures();
    f.account("admin", AccountRole.ADMIN);
    controller = new AdminController(f.mongo, f.auth, f.clock);
    pending = account(AccountRole.DMC_OFFICER, "PENDING");
    f.put(pending);
  }

  UserAccount account(AccountRole role, String status) {
    return new UserAccount(
      "applicant",
      "a@example.test",
      "Officer",
      "hash",
      Set.of(role),
      true,
      f.clock.instant(),
      role,
      status,
      null,
      null,
      null
    );
  }

  @Test
  void approvedDecisionIsConditionalAndReturnsSafeEffectiveRole() {
    var approved = account(AccountRole.DMC_OFFICER, "APPROVED");
    doReturn(approved)
      .when(f.mongo)
      .findAndModify(
        any(Query.class),
        any(Update.class),
        any(FindAndModifyOptions.class),
        eq(UserAccount.class)
      );
    var response = controller.decide(
      f.jwt("admin"),
      pending.id(),
      new AdminController.Decision(true, " Verified ")
    );
    assertThat(response.getBody().roles()).containsExactly(
      AccountRole.DMC_OFFICER
    );
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    var q = org.mockito.ArgumentCaptor.forClass(Query.class);
    var u = org.mockito.ArgumentCaptor.forClass(Update.class);
    verify(f.mongo).findAndModify(
      q.capture(),
      u.capture(),
      any(FindAndModifyOptions.class),
      eq(UserAccount.class)
    );
    assertThat(q.getValue().getQueryObject()).containsEntry(
      "approvalStatus",
      "PENDING"
    );
    assertThat(
      u.getValue().getUpdateObject().get("$set", org.bson.Document.class)
    )
      .containsEntry("approvalNote", "Verified")
      .containsEntry("reviewedBy", "Test admin");
  }

  @Test
  void rejectionKeepsOfficerFeaturesBlocked() {
    doReturn(account(AccountRole.DMC_OFFICER, "REJECTED"))
      .when(f.mongo)
      .findAndModify(
        any(Query.class),
        any(Update.class),
        any(FindAndModifyOptions.class),
        eq(UserAccount.class)
      );
    assertThat(
      controller
        .decide(
          f.jwt("admin"),
          pending.id(),
          new AdminController.Decision(false, "Cannot verify")
        )
        .getBody()
        .roles()
    ).isEmpty();
  }

  @Test
  void staleMissingAndNonOfficerApplicationsCannotBeApproved() {
    assertThatThrownBy(() ->
      controller.decide(
        f.jwt("admin"),
        "missing",
        new AdminController.Decision(true, "Verified")
      )
    ).isInstanceOf(AuthException.class);
    assertThatThrownBy(() ->
      controller.decide(
        f.jwt("admin"),
        pending.id(),
        new AdminController.Decision(true, "Verified")
      )
    )
      .isInstanceOf(AuthException.class)
      .hasMessageContaining("already reviewed");
    for (var a : List.of(
      account(AccountRole.CITIZEN, "PENDING"),
      account(AccountRole.DMC_OFFICER, "APPROVED")
    )) {
      f.put(a);
      assertThatThrownBy(() ->
        controller.decide(
          f.jwt("admin"),
          a.id(),
          new AdminController.Decision(true, "Verified")
        )
      )
        .isInstanceOf(AuthException.class)
        .hasMessageContaining("Only pending");
    }
  }

  @Test
  void applicationListFiltersOfficersAndValidatesPagination() {
    doReturn(1L).when(f.mongo).count(any(Query.class), eq(UserAccount.class));
    doReturn(List.of(pending))
      .when(f.mongo)
      .find(any(Query.class), eq(UserAccount.class));
    var response = controller.list("PENDING", 0, 20);
    assertThat(response.getBody().items().getFirst().roles()).isEmpty();
    assertThat(response.getBody().totalPages()).isEqualTo(1);
    assertThatThrownBy(() -> controller.list("OTHER", 0, 20)).isInstanceOf(
      AuthException.class
    );
    assertThatThrownBy(() -> controller.list("PENDING", -1, 20)).isInstanceOf(
      AuthException.class
    );
    assertThatThrownBy(() -> controller.list("PENDING", 0, 0)).isInstanceOf(
      AuthException.class
    );
    assertThatThrownBy(() -> controller.list("PENDING", 0, 101)).isInstanceOf(
      AuthException.class
    );
  }

  @ParameterizedTest
  @EnumSource(
    value = AccountRole.class,
    names = { "CITIZEN", "COMMUNITY_VOLUNTEER", "DMC_OFFICER", "DUTY_OFFICER" }
  )
  void publicRegistrationHonorsApprovalPolicy(AccountRole role) {
    var settings = AuthServiceTest.SETTINGS;
    var users = mock(UserAccountRepository.class);
    var passwords =
      new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);
    doReturn(
      mock(org.springframework.data.mongodb.core.index.IndexOperations.class)
    )
      .when(f.mongo)
      .indexOps(UserAccount.class);
    when(users.insert(any(UserAccount.class))).thenAnswer(call ->
      call.getArgument(0)
    );
    var config = new AuthSecurityConfiguration();
    var auth = new AuthService(
      users,
      f.mongo,
      passwords,
      config.jwtEncoder(config.jwtSigningKey(settings)),
      settings,
      f.clock
    );
    var response = auth.register(
      new AuthContracts.RegisterRequest(
        "A@example.test",
        " Person ",
        "short",
        role
      )
    );
    boolean officer =
      role == AccountRole.DMC_OFFICER || role == AccountRole.DUTY_OFFICER;
    assertThat(response.approvalStatus()).isEqualTo(
      officer ? "PENDING" : "APPROVED"
    );
    assertThat(response.roles()).hasSize(officer ? 0 : 1);
    assertThatThrownBy(() ->
      auth.register(
        new AuthContracts.RegisterRequest(
          "a@example.test",
          "Admin",
          "short",
          AccountRole.ADMIN
        )
      )
    ).isInstanceOf(AuthException.class);
  }
}
