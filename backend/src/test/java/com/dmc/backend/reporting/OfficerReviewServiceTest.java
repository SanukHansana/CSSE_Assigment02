package com.dmc.backend.reporting;

import com.dmc.backend.models.hazard.HazardReport.UserReference;
import static com.dmc.backend.reporting.ReportFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dmc.backend.auth.*;
import com.dmc.backend.models.hazard.*;
import com.dmc.backend.repository.HazardReportRepository;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class OfficerReviewServiceTest {
    HazardReportRepository reports;
    AuthService auth;
    MongoTemplate mongo;
    EvidenceStorage evidence;
    OfficerReviewService service;
    HazardReport persisted;
    static final CredibilityChecklist PASSED = new CredibilityChecklist(true, true, true, true);
    @BeforeEach void setup() {
        reports = mock(HazardReportRepository.class); auth = mock(AuthService.class);
        mongo = mock(MongoTemplate.class); evidence = mock(EvidenceStorage.class);
        service = new OfficerReviewService(reports, auth, mongo, evidence, CLOCK);
        when(auth.requireActiveAccount("officer")).thenReturn(account("officer", AccountRole.DUTY_OFFICER));
        when(auth.requireActiveAccount("other")).thenReturn(account("other", AccountRole.DUTY_OFFICER));
        persisted = complete(); persisted.submit(CLOCK);
        when(reports.findById(anyString())).thenAnswer(call -> call.getArgument(0).equals(persisted.getId())
                ? Optional.of(copy(persisted)) : Optional.empty());
        when(reports.save(any())).thenAnswer(call -> {
            HazardReport value = call.getArgument(0); persisted = version(value, value.getVersion() + 1); return copy(persisted);
        });
    }
    void reviewing() { persisted.startReview(new UserReference("officer", "Reporter officer"), CLOCK); }
    @Test void startingReviewRecordsAssignedOfficerAndOnePersistedTransition() {
        var result = service.start("officer", persisted.getId(), 0);
        assertThat(result.getStatus()).isEqualTo(ReportStatus.UNDER_REVIEW);
        assertThat(result.getReview().officer().subjectId()).isEqualTo("officer");
        assertThat(result.getVersion()).isEqualTo(1); assertThat(result.getReview().startedAt()).isEqualTo(NOW);
        verify(reports, times(1)).save(any());
    }
    @Test void detailAndPhotoAreReadOnlyAndDraftsArePrivate() {
        assertThat(service.detail("officer", persisted.getId()).getStatus()).isEqualTo(ReportStatus.SUBMITTED);
        when(evidence.read(any())).thenReturn(new byte[]{1,2,3});
        assertThat(service.photo("officer", persisted.getId()).bytes()).hasSize(3);
        verify(reports, never()).save(any());
        persisted = draft();
        assertThatThrownBy(() -> service.detail("officer", persisted.getId())).isInstanceOf(ReportException.class);
        assertThatThrownBy(() -> service.photo("officer", persisted.getId())).isInstanceOf(ReportException.class);
    }
    @Test void citizenCannotUseOfficerService() {
        when(auth.requireActiveAccount("citizen")).thenReturn(account("citizen", AccountRole.CITIZEN));
        assertThatThrownBy(() -> service.start("citizen", persisted.getId(), 0))
                .isInstanceOfSatisfying(ReportException.class, e -> assertThat(e.status().value()).isEqualTo(403));
        verifyNoInteractions(reports);
    }
    @Test void competingOfficerAndStaleVersionCannotOverwriteReview() {
        reviewing();
        assertThatThrownBy(() -> service.update("other", persisted.getId(), new ReviewContracts.UpdateReviewRequest(0L, PASSED, "Edit")))
                .isInstanceOfSatisfying(ReportException.class, e -> assertThat(e.code()).isEqualTo("REVIEW_ASSIGNED_TO_ANOTHER_OFFICER"));
        assertThatThrownBy(() -> service.start("other", persisted.getId(), 0)).isInstanceOf(ReportException.class);
        assertThatThrownBy(() -> service.decide("officer", persisted.getId(), new ReviewContracts.DecisionRequest(9L,
                VerificationDecision.VERIFIED, PASSED, "Edit", null))).isInstanceOf(ReportException.class);
        verify(reports, never()).save(any());
    }
    @Test void savesChecklistAndCommentsWithoutDeciding() {
        reviewing();
        var result = service.update("officer", persisted.getId(), new ReviewContracts.UpdateReviewRequest(0L, PASSED, "Credible"));
        assertThat(result.getReview().checklist()).isEqualTo(PASSED);
        assertThat(result.getReview().comments()).isEqualTo("Credible");
        assertThat(result.getVerification()).isNull(); assertThat(result.getStatus()).isEqualTo(ReportStatus.UNDER_REVIEW);
    }
    @Test void decisionPersistsWorkingDataDecisionStatusAndHistoryInOneSave() {
        reviewing();
        var result = service.decide("officer", persisted.getId(), new ReviewContracts.DecisionRequest(0L,
                VerificationDecision.VERIFIED, PASSED, "Photo and location match", null));
        assertThat(result.getStatus()).isEqualTo(ReportStatus.VERIFIED);
        assertThat(result.isEligibleForAssessment()).isTrue();
        assertThat(result.getVerification().officer().subjectId()).isEqualTo("officer");
        assertThat(result.getVerification().comments()).isEqualTo("Photo and location match");
        assertThat(result.getVerification().decidedAt()).isEqualTo(NOW);
        assertThat(result.getHistory().getLast().type()).isEqualTo(HistoryEventType.VERIFIED);
        verify(reports, times(1)).save(any());
    }
    @Test void rejectionRequiresReasonButDoesNotRequirePassedChecklist() {
        reviewing();
        for (String reason : Arrays.asList(null, "", "   ")) {
            assertThatThrownBy(() -> service.decide("officer", persisted.getId(), new ReviewContracts.DecisionRequest(0L,
                    VerificationDecision.REJECTED, null, null, reason))).isInstanceOf(ReportException.class);
        }
        var result = service.decide("officer", persisted.getId(), new ReviewContracts.DecisionRequest(0L,
                VerificationDecision.REJECTED, null, "Evidence unrelated", "Photo shows another location"));
        assertThat(result.getStatus()).isEqualTo(ReportStatus.REJECTED);
        assertThat(result.getVerification().rejectionReason()).isEqualTo("Photo shows another location");
        assertThat(result.isEligibleForAssessment()).isFalse();
    }
    @Test void verificationFailsForUnassessedChecksOrRejectionReason() {
        reviewing();
        assertThatThrownBy(() -> service.decide("officer", persisted.getId(), new ReviewContracts.DecisionRequest(0L,
                VerificationDecision.VERIFIED, null, null, null))).isInstanceOf(ReportException.class);
        assertThatThrownBy(() -> service.decide("officer", persisted.getId(), new ReviewContracts.DecisionRequest(0L,
                VerificationDecision.VERIFIED, PASSED, null, "Not allowed"))).isInstanceOf(ReportException.class);
        verify(reports, never()).save(any()); assertThat(persisted.getStatus()).isEqualTo(ReportStatus.UNDER_REVIEW);
    }
    @Test void failedDecisionSaveKeepsPersistedReviewAndDoesNotExposeVerifiedState() {
        reviewing();
        doThrow(new DataAccessResourceFailureException("private connection")).when(reports).save(any());
        assertThatThrownBy(() -> service.decide("officer", persisted.getId(), new ReviewContracts.DecisionRequest(0L,
                VerificationDecision.VERIFIED, PASSED, "Credible", null))).isInstanceOf(DataAccessException.class);
        assertThat(persisted.getStatus()).isEqualTo(ReportStatus.UNDER_REVIEW);
        assertThat(persisted.getVerification()).isNull(); assertThat(persisted.isEligibleForAssessment()).isFalse();
        assertThat(persisted.getReview().comments()).isNull();
    }
    @Test void optimisticDecisionFailureIsNotSilentlyRetried() {
        reviewing(); doThrow(new OptimisticLockingFailureException("conflict")).when(reports).save(any());
        assertThatThrownBy(() -> service.decide("officer", persisted.getId(), new ReviewContracts.DecisionRequest(0L,
                VerificationDecision.REJECTED, null, null, "Unrelated photo"))).isInstanceOf(OptimisticLockingFailureException.class);
        verify(reports, times(1)).save(any()); assertThat(persisted.getVerification()).isNull();
    }
    @Test void decisionRequiresReviewAndFinalDecisionsCannotBeOverwritten() {
        assertThatThrownBy(() -> service.decide("officer", persisted.getId(), new ReviewContracts.DecisionRequest(0L,
                VerificationDecision.VERIFIED, PASSED, null, null))).isInstanceOf(ReportException.class);
        reviewing(); service.decide("officer", persisted.getId(), new ReviewContracts.DecisionRequest(0L,
                VerificationDecision.REJECTED, null, null, "Wrong photo"));
        assertThatThrownBy(() -> service.decide("officer", persisted.getId(), new ReviewContracts.DecisionRequest(1L,
                VerificationDecision.VERIFIED, PASSED, null, null))).isInstanceOf(ReportException.class);
        verify(reports, times(1)).save(any());
    }
    @Test void queueUsesBoundedLiteralSearchStatusFilterAndStableOrdering() {
        when(mongo.count(any(Query.class), eq(HazardReport.class))).thenReturn(21L);
        when(mongo.find(any(Query.class), eq(HazardReport.class))).thenReturn(List.of(copy(persisted)));
        var result = service.queue("officer", ".*", null, HazardType.FLOODING, 1, 20);
        assertThat(result.totalPages()).isEqualTo(2); assertThat(result.items()).hasSize(1);
        var query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(query.capture(), eq(HazardReport.class));
        assertThat(query.getValue().getSkip()).isEqualTo(20);
        assertThat(query.getValue().getLimit()).isEqualTo(20);
        assertThat(query.getValue().getQueryObject().get("status")).isEqualTo(ReportStatus.SUBMITTED);
        assertThat(query.getValue().getQueryObject().toString()).contains("\\Q.*\\E");
        assertThat(query.getValue().getSortObject().keySet()).containsExactly("submittedAt", "_id");
        assertThatThrownBy(() -> service.queue("officer", null, ReportStatus.DRAFT, null, 0, 20)).isInstanceOf(ReportException.class);
        assertThatThrownBy(() -> service.queue("officer", "a".repeat(101), null, null, 0, 20)).isInstanceOf(ReportException.class);
        assertThatThrownBy(() -> service.queue("officer", null, null, null, -1, 20)).isInstanceOf(ReportException.class);
        assertThatThrownBy(() -> service.queue("officer", null, null, null, 0, 101)).isInstanceOf(ReportException.class);
    }
}
