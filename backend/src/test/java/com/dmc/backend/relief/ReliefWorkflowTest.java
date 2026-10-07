package com.dmc.backend.relief;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dmc.backend.relief.ReliefModels.*;
import com.dmc.backend.support.WorkflowFixtures;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ReliefWorkflowTest {

  WorkflowFixtures fixture;
  ReliefController controller;
  Resource resource;
  Shelter shelter;

  @BeforeEach
  void setup() {
    fixture = new WorkflowFixtures();
    controller = new ReliefController(
      fixture.mongo,
      fixture.auth,
      fixture.clock,
      fixture.factory
    );
    resource = controller.createResource(
      fixture.jwt("officer"),
      new ReliefController.ResourceRequest(
        "Water",
        "WATER",
        "Colombo",
        100L,
        "bottles",
        null
      )
    );
    shelter = controller.createShelter(
      fixture.jwt("officer"),
      new ReliefController.ShelterRequest(
        "Hall",
        "Colombo",
        20L,
        5L,
        ShelterStatus.OPEN,
        null
      )
    );
  }

  void failure(Runnable work, HttpStatus status) {
    assertThatThrownBy(work::run).isInstanceOfSatisfying(
      ResponseStatusException.class,
      error -> assertThat(error.getStatusCode()).isEqualTo(status)
    );
  }

  ReliefController.AllocationRequest allocation(
    String id,
    long qty,
    Long version
  ) {
    return new ReliefController.AllocationRequest(
      id,
      version,
      shelter.id(),
      qty,
      "Residents need supplies"
    );
  }

  @Test
  void allocationDeductsStockAndCommitsBothHistoriesAtomically() {
    var result = controller.allocate(
      fixture.jwt("officer"),
      resource.id(),
      allocation("allocation", 20, 0L)
    );
    assertThat(result.availableQuantity()).isEqualTo(80);
    assertThat(result.allocations()).hasSize(1);
    assertThat(fixture.get(Shelter.class, shelter.id()).history())
      .singleElement()
      .satisfies(event ->
        assertThat(event.officer()).isEqualTo("Test officer")
      );
    verify(fixture.session).commitTransaction();
    verify(fixture.session, never()).abortTransaction();
    assertThat(
      controller
        .allocate(
          fixture.jwt("officer"),
          resource.id(),
          allocation("allocation", 20, 0L)
        )
        .version()
    ).isEqualTo(result.version());
    failure(
      () ->
        controller.allocate(
          fixture.jwt("officer"),
          resource.id(),
          allocation("allocation", 21, result.version())
        ),
      HttpStatus.CONFLICT
    );
  }

  @Test
  void insufficientStockClosedShelterAndStaleVersionDoNotAllocate() {
    failure(
      () ->
        controller.allocate(
          fixture.jwt("officer"),
          resource.id(),
          allocation("bad", 101, 0L)
        ),
      HttpStatus.CONFLICT
    );
    failure(
      () ->
        controller.allocate(
          fixture.jwt("officer"),
          resource.id(),
          allocation("bad", 1, 9L)
        ),
      HttpStatus.CONFLICT
    );
    fixture.put(
      new Shelter(
        shelter.id(),
        0L,
        "Hall",
        "Colombo",
        20,
        5,
        ShelterStatus.CLOSED,
        "Officer",
        fixture.clock.instant(),
        List.of()
      )
    );
    failure(
      () ->
        controller.allocate(
          fixture.jwt("officer"),
          resource.id(),
          allocation("bad", 1, 0L)
        ),
      HttpStatus.CONFLICT
    );
    assertThat(
      fixture.get(Resource.class, resource.id()).availableQuantity()
    ).isEqualTo(100);
  }

  @Test
  void failureWritingShelterRollsBackStockAndAllocationHistory() {
    doThrow(new DataAccessResourceFailureException("offline"))
      .when(fixture.mongo)
      .save(any(Shelter.class));
    assertThatThrownBy(() ->
      controller.allocate(
        fixture.jwt("officer"),
        resource.id(),
        allocation("fail", 20, 0L)
      )
    ).isInstanceOf(DataAccessResourceFailureException.class);
    verify(fixture.session).abortTransaction();
    assertThat(
      fixture.get(Resource.class, resource.id()).allocations()
    ).isEmpty();
    assertThat(
      fixture.get(Resource.class, resource.id()).availableQuantity()
    ).isEqualTo(100);
  }

  @Test
  void arrivalsDeparturesAndDuplicateRetriesPreserveCapacity() {
    var full = controller.occupancy(
      fixture.jwt("officer"),
      shelter.id(),
      new ReliefController.OccupancyRequest("arrive", 0L, 15L, "Arrivals")
    );
    assertThat(full.status()).isEqualTo(ShelterStatus.FULL);
    assertThat(full.occupancy()).isEqualTo(20);
    var replay = controller.occupancy(
      fixture.jwt("officer"),
      shelter.id(),
      new ReliefController.OccupancyRequest("arrive", 0L, 15L, "Arrivals")
    );
    assertThat(replay.version()).isEqualTo(full.version());
    var departed = controller.occupancy(
      fixture.jwt("officer"),
      shelter.id(),
      new ReliefController.OccupancyRequest(
        "depart",
        full.version(),
        -4L,
        "Departures"
      )
    );
    assertThat(departed.status()).isEqualTo(ShelterStatus.OPEN);
    assertThat(departed.occupancy()).isEqualTo(16);
    failure(
      () ->
        controller.occupancy(
          fixture.jwt("officer"),
          shelter.id(),
          new ReliefController.OccupancyRequest("arrive", 2L, 1L, "Different")
        ),
      HttpStatus.CONFLICT
    );
  }

  @Test
  void invalidOccupancyChangesAndClosedAdmissionsAreRejected() {
    for (long delta : new long[] { 0, -6, 16 })
      failure(
        () ->
          controller.occupancy(
            fixture.jwt("officer"),
            shelter.id(),
            new ReliefController.OccupancyRequest("bad", 0L, delta, "Invalid")
          ),
        HttpStatus.BAD_REQUEST
      );
    fixture.put(
      new Shelter(
        shelter.id(),
        0L,
        "Hall",
        "Colombo",
        20,
        5,
        ShelterStatus.CLOSED,
        "Officer",
        fixture.clock.instant(),
        List.of()
      )
    );
    failure(
      () ->
        controller.occupancy(
          fixture.jwt("officer"),
          shelter.id(),
          new ReliefController.OccupancyRequest("admit", 0L, 1L, "Closed")
        ),
      HttpStatus.CONFLICT
    );
    assertThat(
      controller
        .occupancy(
          fixture.jwt("officer"),
          shelter.id(),
          new ReliefController.OccupancyRequest("depart", 0L, -1L, "Departures")
        )
        .status()
    ).isEqualTo(ShelterStatus.CLOSED);
    fixture.put(
      new Shelter(
        shelter.id(),
        0L,
        "Hall",
        "Colombo",
        Long.MAX_VALUE,
        Long.MAX_VALUE,
        ShelterStatus.FULL,
        "Officer",
        fixture.clock.instant(),
        List.of()
      )
    );
    failure(
      () ->
        controller.occupancy(
          fixture.jwt("officer"),
          shelter.id(),
          new ReliefController.OccupancyRequest("overflow", 0L, 1L, "Overflow")
        ),
      HttpStatus.BAD_REQUEST
    );
  }

  @Test
  void administrativeUpdatesKeepHistoryAndRequireVersion() {
    failure(
      () ->
        controller.updateResource(
          fixture.jwt("officer"),
          resource.id(),
          new ReliefController.ResourceRequest(
            "Water",
            "WATER",
            "Town",
            10L,
            "bottles",
            null
          )
        ),
      HttpStatus.BAD_REQUEST
    );
    var updated = controller.updateResource(
      fixture.jwt("officer"),
      resource.id(),
      new ReliefController.ResourceRequest(
        "Water",
        "WATER",
        "Town",
        120L,
        "bottles",
        0L
      )
    );
    assertThat(updated.availableQuantity()).isEqualTo(120);
    assertThat(updated.history()).hasSize(1);
    var result = controller.updateShelter(
      fixture.jwt("officer"),
      shelter.id(),
      new ReliefController.ShelterRequest(
        "Hall",
        "Town",
        30L,
        6L,
        ShelterStatus.OPEN,
        0L
      )
    );
    assertThat(result.capacity()).isEqualTo(30);
    assertThat(result.history()).hasSize(1);
  }

  @Test
  void inconsistentShelterStatusAndInvalidPaginationFail() {
    for (ReliefController.ShelterRequest body : List.of(
      new ReliefController.ShelterRequest(
        "Hall",
        "Town",
        5L,
        6L,
        ShelterStatus.OPEN,
        null
      ),
      new ReliefController.ShelterRequest(
        "Hall",
        "Town",
        5L,
        2L,
        ShelterStatus.FULL,
        null
      ),
      new ReliefController.ShelterRequest(
        "Hall",
        "Town",
        5L,
        5L,
        ShelterStatus.OPEN,
        null
      )
    ))
      failure(
        () -> controller.createShelter(fixture.jwt("officer"), body),
        HttpStatus.BAD_REQUEST
      );
    assertThat(controller.resources(0, 10).items()).hasSize(1);
    assertThat(controller.shelters(0, 10).totalElements()).isEqualTo(1);
    assertThat(controller.resource(resource.id())).isEqualTo(resource);
    assertThat(controller.shelter(shelter.id())).isEqualTo(shelter);
    failure(() -> controller.resources(-1, 10), HttpStatus.BAD_REQUEST);
    failure(() -> controller.shelters(0, 0), HttpStatus.BAD_REQUEST);
    failure(() -> controller.resources(0, 101), HttpStatus.BAD_REQUEST);
    failure(() -> controller.shelter("missing"), HttpStatus.NOT_FOUND);
  }
}
