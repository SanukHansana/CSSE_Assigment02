package com.dmc.backend.rescue;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dmc.backend.rescue.RescueController.*;
import com.dmc.backend.rescue.RescueModels.*;
import com.dmc.backend.support.WorkflowFixtures;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.server.ResponseStatusException;

class RescueWorkflowTest {

  WorkflowFixtures f;
  RescueController c;
  Incident incident;
  Team team;

  @BeforeEach
  void setup() {
    f = new WorkflowFixtures();
    c = new RescueController(f.mongo, f.auth, f.clock, f.factory);
    incident = c.createIncident(f.jwt("officer"), input(null));
    team = c.createTeam(
      f.jwt("officer"),
      teamInput(TeamStatus.AVAILABLE, null)
    );
  }

  IncidentRequest input(Long v) {
    return new IncidentRequest(
      IncidentType.FLOOD_RESCUE,
      Priority.CRITICAL,
      " Flood rescue ",
      " Colombo ",
      6.9,
      79.9,
      10L,
      v
    );
  }

  TeamRequest teamInput(TeamStatus s, Long v) {
    return new TeamRequest(
      " Team ",
      IncidentType.FLOOD_RESCUE,
      " Colombo ",
      6.9,
      79.9,
      s,
      v
    );
  }

  Incident assign() {
    incident = c.assign(
      f.jwt("officer"),
      incident.id(),
      new AssignRequest(
        "assign",
        incident.version(),
        team.id(),
        team.version(),
        "Dispatch"
      )
    );
    return incident;
  }

  Incident progress(String id, AssignmentStatus s) {
    incident = c.progress(
      f.jwt("officer"),
      incident.id(),
      "assign",
      new ProgressRequest(
        id,
        incident.version(),
        s,
        "Progress",
        7.0,
        80.0,
        " Site "
      )
    );
    return incident;
  }

  void fail(int status, Runnable action) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(
      ResponseStatusException.class,
      e -> assertThat(e.getStatusCode().value()).isEqualTo(status)
    );
  }

  @Test
  void completeRescueLifecycleReleasesTeamBeforeClosing() {
    assign();
    assertThat(f.get(Team.class, team.id()).status()).isEqualTo(
      TeamStatus.DEPLOYED
    );
    progress("enroute", AssignmentStatus.EN_ROUTE);
    progress("onsite", AssignmentStatus.ON_SITE);
    assertThat(f.get(Team.class, team.id()).status()).isEqualTo(
      TeamStatus.BUSY
    );
    progress("done", AssignmentStatus.COMPLETED);
    assertThat(f.get(Team.class, team.id()).activeIncidentId()).isNull();
    incident = c.complete(
      f.jwt("officer"),
      incident.id(),
      new CompleteRequest(
        "resolve",
        incident.version(),
        IncidentStatus.RESOLVED,
        "All rescued"
      )
    );
    incident = c.complete(
      f.jwt("officer"),
      incident.id(),
      new CompleteRequest(
        "close",
        incident.version(),
        IncidentStatus.CLOSED,
        "Closed"
      )
    );
    assertThat(incident.status()).isEqualTo(IncidentStatus.CLOSED);
    assertThat(incident.history()).hasSize(7);
    verify(f.session, atLeastOnce()).commitTransaction();
  }

  @Test
  void duplicateDispatchDoesNotDeployTwiceAndChangedInputConflicts() {
    var body = new AssignRequest("assign", 0L, team.id(), 0L, "Dispatch");
    assign();
    assertThat(c.assign(f.jwt("officer"), incident.id(), body)).isEqualTo(
      incident
    );
    fail(409, () ->
      c.assign(
        f.jwt("officer"),
        incident.id(),
        new AssignRequest("assign", 0L, team.id(), 0L, "Changed")
      )
    );
    assertThat(incident.assignments()).hasSize(1);
  }

  @Test
  void cancellationReleasesTeamButCannotResolveWithoutCompletedWork() {
    assign();
    progress("cancel", AssignmentStatus.CANCELLED);
    assertThat(f.get(Team.class, team.id()).status()).isEqualTo(
      TeamStatus.AVAILABLE
    );
    fail(409, () ->
      c.complete(
        f.jwt("officer"),
        incident.id(),
        new CompleteRequest(
          "resolve",
          incident.version(),
          IncidentStatus.RESOLVED,
          "No rescue"
        )
      )
    );
    fail(409, () -> progress("restart", AssignmentStatus.EN_ROUTE));
  }

  @Test
  void transitionAndCoordinatesAreValidatedBeforeAnyWrite() {
    assign();
    fail(409, () -> progress("skip", AssignmentStatus.COMPLETED));
    fail(404, () ->
      c.progress(
        f.jwt("officer"),
        incident.id(),
        "missing",
        new ProgressRequest(
          "p",
          1L,
          AssignmentStatus.EN_ROUTE,
          "test",
          null,
          null,
          null
        )
      )
    );
    fail(400, () ->
      c.progress(
        f.jwt("officer"),
        incident.id(),
        "assign",
        new ProgressRequest(
          "p",
          1L,
          AssignmentStatus.EN_ROUTE,
          "test",
          7.0,
          null,
          null
        )
      )
    );
    fail(400, () ->
      c.progress(
        f.jwt("officer"),
        incident.id(),
        "assign",
        new ProgressRequest(
          "p",
          1L,
          AssignmentStatus.EN_ROUTE,
          "test",
          Double.NaN,
          80.0,
          null
        )
      )
    );
    fail(400, () ->
      c.progress(
        f.jwt("officer"),
        incident.id(),
        "assign",
        new ProgressRequest(
          "p",
          1L,
          AssignmentStatus.EN_ROUTE,
          "test",
          7.0,
          Double.POSITIVE_INFINITY,
          null
        )
      )
    );
    assertThat(f.get(Team.class, team.id()).status()).isEqualTo(
      TeamStatus.DEPLOYED
    );
  }

  @Test
  void missingVersionStaleRecordUnavailableTeamAndAssignedEditsFail() {
    fail(400, () ->
      c.updateIncident(f.jwt("officer"), incident.id(), input(null))
    );
    fail(409, () ->
      c.updateIncident(f.jwt("officer"), incident.id(), input(9L))
    );
    fail(400, () ->
      c.createTeam(f.jwt("officer"), teamInput(TeamStatus.BUSY, null))
    );
    team = c.updateTeam(
      f.jwt("officer"),
      team.id(),
      teamInput(TeamStatus.UNAVAILABLE, 0L)
    );
    fail(409, () ->
      c.assign(
        f.jwt("officer"),
        incident.id(),
        new AssignRequest("a", 0L, team.id(), 1L, "Dispatch")
      )
    );
    team = c.updateTeam(
      f.jwt("officer"),
      team.id(),
      teamInput(TeamStatus.AVAILABLE, 1L)
    );
    assign();
    fail(409, () ->
      c.updateTeam(
        f.jwt("officer"),
        team.id(),
        teamInput(TeamStatus.AVAILABLE, 3L)
      )
    );
  }

  @Test
  void progressWithoutCoordinatesRetainsLastLocationAndReplayDoesNotMutate() {
    assign();
    var body = new ProgressRequest(
      "move",
      1L,
      AssignmentStatus.EN_ROUTE,
      "Moving",
      null,
      null,
      " "
    );
    incident = c.progress(f.jwt("officer"), incident.id(), "assign", body);
    assertThat(f.get(Team.class, team.id()).location()).isEqualTo("Colombo");
    assertThat(
      c.progress(f.jwt("officer"), incident.id(), "assign", body)
    ).isEqualTo(incident);
    fail(409, () ->
      c.progress(
        f.jwt("officer"),
        incident.id(),
        "assign",
        new ProgressRequest(
          "move",
          1L,
          AssignmentStatus.EN_ROUTE,
          "Changed",
          null,
          null,
          null
        )
      )
    );
  }

  @Test
  void persistenceFailureRollsBackTeamDeployment() {
    doThrow(new DataAccessResourceFailureException("Offline"))
      .when(f.mongo)
      .save(any(Incident.class));
    assertThatThrownBy(this::assign).isInstanceOf(
      DataAccessResourceFailureException.class
    );
    assertThat(f.get(Team.class, team.id()).status()).isEqualTo(
      TeamStatus.AVAILABLE
    );
    assertThat(f.get(Incident.class, incident.id()).assignments()).isEmpty();
    verify(f.session).abortTransaction();
  }

  @Test
  void listsUpdatesMissingRecordsAndPrematureCompletion() {
    assertThat(c.incidents(0, 10).totalElements()).isEqualTo(1);
    assertThat(c.teams(0, 10).items()).contains(team);
    assertThat(c.incident(incident.id())).isEqualTo(incident);
    assertThat(c.team(team.id())).isEqualTo(team);
    assertThat(
      c.updateIncident(f.jwt("officer"), incident.id(), input(0L)).history()
    ).hasSize(2);
    fail(404, () -> c.team("missing"));
    fail(400, () -> c.incidents(-1, 10));
    fail(400, () -> c.teams(0, 0));
    fail(400, () -> c.teams(0, 101));
    fail(409, () ->
      c.complete(
        f.jwt("officer"),
        incident.id(),
        new CompleteRequest("close", 1L, IncidentStatus.CLOSED, "Close")
      )
    );
    fail(409, () ->
      c.complete(
        f.jwt("officer"),
        incident.id(),
        new CompleteRequest("r", 1L, IncidentStatus.RESOLVED, "Resolve")
      )
    );
  }
}
