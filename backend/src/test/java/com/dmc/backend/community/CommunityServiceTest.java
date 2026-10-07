package com.dmc.backend.community;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dmc.backend.auth.*;
import com.dmc.backend.community.CommunityNeed.*;
import com.dmc.backend.support.WorkflowFixtures;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CommunityServiceTest {

  WorkflowFixtures fixture;
  CommunityService service;
  CommunityNeed need;

  @BeforeEach
  void setup() {
    fixture = new WorkflowFixtures();
    fixture.account("citizen", AccountRole.CITIZEN);
    fixture.account("volunteer", AccountRole.COMMUNITY_VOLUNTEER);
    fixture.account("other", AccountRole.CITIZEN);
    fixture.account("volunteer2", AccountRole.COMMUNITY_VOLUNTEER);
    service = new CommunityService(fixture.mongo, fixture.auth, fixture.clock);
    need = service.create(
      "citizen",
      new CommunityController.CreateRequest(
        " Water ",
        "WATER",
        10L,
        "bottles",
        "Clean drinking water",
        "Colombo"
      )
    );
    clearInvocations(fixture.mongo);
  }

  CommunityController.OfferRequest offer(
    String id,
    long quantity,
    Long version
  ) {
    return new CommunityController.OfferRequest(
      id,
      version,
      quantity,
      "I can give my own supplies"
    );
  }

  CommunityController.ActionRequest action(
    String id,
    String action,
    Long version
  ) {
    return new CommunityController.ActionRequest(id, version, action);
  }

  long version() {
    return fixture.get(CommunityNeed.class, need.id()).version();
  }

  void offer() {
    service.offer("volunteer", need.id(), offer("offer1", 10, version()));
  }

  void accept() {
    offer();
    service.act(
      "citizen",
      need.id(),
      "offer1",
      action("accept1", "ACCEPT", version())
    );
  }

  void delivered() {
    accept();
    service.act(
      "volunteer",
      need.id(),
      "offer1",
      action("deliver1", "DELIVER", version())
    );
  }

  void expectStatus(Runnable operation, HttpStatus status) {
    assertThatThrownBy(operation::run).isInstanceOfSatisfying(
      ResponseStatusException.class,
      error -> assertThat(error.getStatusCode()).isEqualTo(status)
    );
  }

  @Test
  void citizenPostsNeedWithServerIdentityAndNoOfficialStockChanges() {
    assertThat(need.item()).isEqualTo("Water");
    assertThat(need.citizenId()).isEqualTo("citizen");
    assertThat(need.status()).isEqualTo(Status.OPEN);
    assertThat(need.history())
      .singleElement()
      .satisfies(event ->
        assertThat(event.at()).isEqualTo(fixture.clock.instant())
      );
    assertThat(fixture.records).hasSize(1);
  }

  @Test
  void volunteersCanDiscoverOpenNeedsButCitizensOnlyListTheirOwn() {
    assertThat(service.list("volunteer", "available", 0, 10).items())
      .singleElement()
      .satisfies(view -> assertThat(view.mine()).isFalse());
    assertThat(
      service.list("citizen", "mine", 0, 10).totalElements()
    ).isEqualTo(1);
    assertThat(service.list("other", "mine", 0, 10).items()).isEmpty();
    expectStatus(
      () -> service.list("citizen", "available", 0, 10),
      HttpStatus.FORBIDDEN
    );
  }

  @Test
  void completeSupportFlowNeedsCitizenReceiptBeforeFulfilment() {
    delivered();
    assertThat(service.detail("citizen", need.id()).status()).isEqualTo(
      Status.OPEN
    );
    assertThat(service.detail("citizen", need.id()).received()).isZero();
    var received = service.act(
      "citizen",
      need.id(),
      "offer1",
      action("receipt1", "RECEIVE", version())
    );
    assertThat(received.status()).isEqualTo(Status.FULFILLED);
    assertThat(received.received()).isEqualTo(10);
    assertThat(received.remainingToCommit()).isZero();
    assertThat(received.offers())
      .singleElement()
      .satisfies(value ->
        assertThat(value.status()).isEqualTo(OfferStatus.RECEIVED)
      );
  }

  @Test
  void partialReceiptKeepsNeedOpenAndPermitsRemainingResources() {
    service.offer("volunteer", need.id(), offer("offer1", 4, version()));
    service.act(
      "citizen",
      need.id(),
      "offer1",
      action("a", "ACCEPT", version())
    );
    service.act(
      "volunteer",
      need.id(),
      "offer1",
      action("d", "DELIVER", version())
    );
    var result = service.act(
      "citizen",
      need.id(),
      "offer1",
      action("r", "RECEIVE", version())
    );
    assertThat(result.status()).isEqualTo(Status.OPEN);
    assertThat(result.received()).isEqualTo(4);
    assertThat(result.remainingToCommit()).isEqualTo(6);
  }

  @Test
  void acceptingAnotherOfferCannotOverCommitTheRequest() {
    service.offer("volunteer", need.id(), offer("first", 7, version()));
    service.offer("volunteer2", need.id(), offer("second", 7, version()));
    service.act(
      "citizen",
      need.id(),
      "first",
      action("accept", "ACCEPT", version())
    );
    expectStatus(
      () ->
        service.act(
          "citizen",
          need.id(),
          "second",
          action("over", "ACCEPT", version())
        ),
      HttpStatus.CONFLICT
    );
    assertThat(
      service.detail("citizen", need.id()).remainingToCommit()
    ).isEqualTo(3);
  }

  @Test
  void volunteersOnlySeeTheirOffersAndNoInternalAccountIds() {
    offer();
    service.offer("volunteer2", need.id(), offer("offer2", 2, version()));
    var view = service.detail("volunteer", need.id());
    assertThat(view.offers()).hasSize(1);
    assertThat(view.offers().getFirst().mine()).isTrue();
    assertThat(view.history()).noneMatch(event ->
      event.detail().contains("volunteer")
    );
    assertThat(service.list("volunteer", "offers", 0, 10).items()).hasSize(1);
    assertThat(service.detail("citizen", need.id()).offers()).hasSize(2);
  }

  @Test
  void duplicateOfferAndDecisionRetriesDoNotRepeatWrites() {
    offer();
    long afterOffer = version();
    service.offer("volunteer", need.id(), offer("offer1", 10, 0L));
    assertThat(version()).isEqualTo(afterOffer);
    service.act(
      "citizen",
      need.id(),
      "offer1",
      action("accept1", "ACCEPT", version())
    );
    long afterAccept = version();
    service.act(
      "citizen",
      need.id(),
      "offer1",
      action("accept1", "ACCEPT", 0L)
    );
    assertThat(version()).isEqualTo(afterAccept);
    expectStatus(
      () ->
        service.offer("volunteer", need.id(), offer("offer1", 9, version())),
      HttpStatus.CONFLICT
    );
  }

  @Test
  void withdrawalReleasesReservationsAndDeclineIsRecorded() {
    accept();
    var result = service.act(
      "volunteer",
      need.id(),
      "offer1",
      action("withdraw", "WITHDRAW", version())
    );
    assertThat(result.remainingToCommit()).isEqualTo(10);
    assertThat(result.offers().getFirst().status()).isEqualTo(
      OfferStatus.WITHDRAWN
    );
    service.offer("volunteer2", need.id(), offer("offer2", 10, version()));
    assertThat(
      service
        .act(
          "citizen",
          need.id(),
          "offer2",
          action("decline", "DECLINE", version())
        )
        .offers()
        .get(1)
        .status()
    ).isEqualTo(OfferStatus.DECLINED);
  }

  @Test
  void cancellingClosesPendingOffersAndRetryIsIdempotent() {
    offer();
    var body = new CommunityController.CancelRequest("cancel1", version());
    var result = service.cancel("citizen", need.id(), body);
    assertThat(result.status()).isEqualTo(Status.CANCELLED);
    assertThat(result.offers().getFirst().status()).isEqualTo(
      OfferStatus.DECLINED
    );
    assertThat(service.cancel("citizen", need.id(), body).version()).isEqualTo(
      result.version()
    );
    expectStatus(
      () -> service.detail("volunteer2", need.id()),
      HttpStatus.NOT_FOUND
    );
    assertThat(service.detail("volunteer", need.id()).status()).isEqualTo(
      Status.CANCELLED
    );
  }

  @Test
  void acceptedOrDeliveredOffersBlockCancellation() {
    accept();
    expectStatus(
      () ->
        service.cancel(
          "citizen",
          need.id(),
          new CommunityController.CancelRequest("cancel", version())
        ),
      HttpStatus.CONFLICT
    );
    service.act(
      "volunteer",
      need.id(),
      "offer1",
      action("d", "DELIVER", version())
    );
    expectStatus(
      () ->
        service.cancel(
          "citizen",
          need.id(),
          new CommunityController.CancelRequest("cancel", version())
        ),
      HttpStatus.CONFLICT
    );
  }

  @Test
  void identityAndRoleBoundariesPreventUnauthorizedChanges() {
    expectStatus(
      () ->
        service.create(
          "volunteer",
          new CommunityController.CreateRequest(
            "Food",
            "FOOD",
            1L,
            "pack",
            "Food",
            "Colombo"
          )
        ),
      HttpStatus.FORBIDDEN
    );
    expectStatus(
      () -> service.offer("citizen", need.id(), offer("bad", 1, version())),
      HttpStatus.FORBIDDEN
    );
    expectStatus(
      () -> service.detail("other", need.id()),
      HttpStatus.NOT_FOUND
    );
    expectStatus(
      () ->
        service.cancel(
          "other",
          need.id(),
          new CommunityController.CancelRequest("bad", version())
        ),
      HttpStatus.FORBIDDEN
    );
    offer();
    expectStatus(
      () ->
        service.act(
          "other",
          need.id(),
          "offer1",
          action("bad", "ACCEPT", version())
        ),
      HttpStatus.FORBIDDEN
    );
    expectStatus(
      () ->
        service.act(
          "volunteer2",
          need.id(),
          "offer1",
          action("bad", "DELIVER", version())
        ),
      HttpStatus.FORBIDDEN
    );
    fixture.account(
      "citizen",
      AccountRole.CITIZEN,
      AccountRole.COMMUNITY_VOLUNTEER
    );
    expectStatus(
      () -> service.offer("citizen", need.id(), offer("self", 1, version())),
      HttpStatus.FORBIDDEN
    );
  }

  @Test
  void pendingVolunteerHasNoEffectivePermissions() {
    UserAccount pending = new UserAccount(
      "volunteer",
      "v@example.test",
      "Applicant",
      "hash",
      Set.of(AccountRole.COMMUNITY_VOLUNTEER),
      true,
      fixture.clock.instant(),
      AccountRole.COMMUNITY_VOLUNTEER,
      "PENDING",
      null,
      null,
      null
    );
    when(fixture.auth.requireActiveAccount("volunteer")).thenReturn(pending);
    expectStatus(
      () -> service.offer("volunteer", need.id(), offer("bad", 1, version())),
      HttpStatus.FORBIDDEN
    );
  }

  @Test
  void staleMissingVersionsAndExcessiveQuantitiesLeaveNeedUnchanged() {
    expectStatus(
      () -> service.offer("volunteer", need.id(), offer("bad", 1, 99L)),
      HttpStatus.CONFLICT
    );
    expectStatus(
      () -> service.offer("volunteer", need.id(), offer("bad", 1, null)),
      HttpStatus.CONFLICT
    );
    expectStatus(
      () -> service.offer("volunteer", need.id(), offer("bad", 11, version())),
      HttpStatus.CONFLICT
    );
    expectStatus(
      () -> service.offer("volunteer", need.id(), offer("bad", 0, version())),
      HttpStatus.CONFLICT
    );
    assertThat(version()).isZero();
    assertThat(fixture.get(CommunityNeed.class, need.id()).offers()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
    strings = { "ACCEPT", "DECLINE", "DELIVER", "RECEIVE", "WITHDRAW" }
  )
  void terminalOfferCannotTransitionAgain(String operation) {
    offer();
    service.act(
      "volunteer",
      need.id(),
      "offer1",
      action("withdraw", "WITHDRAW", version())
    );
    String subject = List.of("DELIVER", "WITHDRAW").contains(operation)
      ? "volunteer"
      : "citizen";
    expectStatus(
      () ->
        service.act(
          subject,
          need.id(),
          "offer1",
          action("bad", operation, version())
        ),
      HttpStatus.CONFLICT
    );
  }

  @Test
  void unknownActionsMissingRecordsInvalidPagesAndClosedNeedsFailCleanly() {
    expectStatus(
      () -> service.detail("citizen", "missing"),
      HttpStatus.NOT_FOUND
    );
    expectStatus(
      () -> service.list("citizen", "unknown", 0, 10),
      HttpStatus.BAD_REQUEST
    );
    for (int[] bounds : List.of(
      new int[] { -1, 10 },
      new int[] { 0, 0 },
      new int[] { 0, 101 }
    ))
      expectStatus(
        () -> service.list("citizen", "mine", bounds[0], bounds[1]),
        HttpStatus.BAD_REQUEST
      );
    offer();
    expectStatus(
      () ->
        service.act(
          "citizen",
          need.id(),
          "missing",
          action("bad", "ACCEPT", version())
        ),
      HttpStatus.NOT_FOUND
    );
    expectStatus(
      () ->
        service.act(
          "citizen",
          need.id(),
          "offer1",
          action("bad", "UNKNOWN", version())
        ),
      HttpStatus.BAD_REQUEST
    );
    service.cancel(
      "citizen",
      need.id(),
      new CommunityController.CancelRequest("cancel", version())
    );
    expectStatus(
      () -> service.offer("volunteer", need.id(), offer("later", 1, version())),
      HttpStatus.CONFLICT
    );
  }

  @Test
  void persistenceFailureDoesNotMutateTheStoredNeedOrAcknowledgeSuccess() {
    when(fixture.mongo.save(any(CommunityNeed.class))).thenThrow(
      new DataAccessResourceFailureException("offline")
    );
    assertThatThrownBy(() ->
      service.offer("volunteer", need.id(), offer("first", 1, version()))
    ).isInstanceOf(DataAccessResourceFailureException.class);
    assertThat(fixture.get(CommunityNeed.class, need.id()).offers()).isEmpty();
    assertThat(version()).isZero();
  }
}
