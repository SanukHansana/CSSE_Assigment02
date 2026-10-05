package com.dmc.backend.reporting;

import com.dmc.backend.models.hazard.HazardReport.PhotoEvidence;
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
import org.springframework.data.domain.*;
import org.springframework.mock.web.MockMultipartFile;

class GroundReportServiceTest {
    HazardReportRepository reports;
    AuthService auth;
    EvidenceStorage storage;
    GroundReportService service;
    HazardReport persisted;

    @BeforeEach void setup() {
        reports = mock(HazardReportRepository.class); auth = mock(AuthService.class); storage = mock(EvidenceStorage.class);
        service = new GroundReportService(reports, auth, storage, CLOCK);
        when(auth.requireActiveAccount("citizen")).thenReturn(account("citizen", AccountRole.CITIZEN));
        when(reports.save(any())).thenAnswer(call -> {
            HazardReport report = call.getArgument(0);
            persisted = version(report, report.getVersion() == null ? 0 : report.getVersion() + 1);
            return copy(persisted);
        });
    }
    void load(HazardReport report) {
        persisted = copy(report);
        when(reports.findByIdAndReporterUserSubjectId(report.getId(), "citizen"))
                .thenAnswer(call -> Optional.of(copy(persisted)));
    }
    @Test void createsIncompleteDraftUsingAuthenticatedCitizen() {
        var result = service.create("citizen", new ReportContracts.CreateDraftRequest(null, null, null, NOW.minusSeconds(10), null));
        assertThat(result.getStatus()).isEqualTo(ReportStatus.DRAFT);
        assertThat(result.getVersion()).isZero();
        assertThat(result.getReporter().user().subjectId()).isEqualTo("citizen");
        assertThat(result.getSource()).isEqualTo(ReportSource.WEB_PORTAL);
        assertThat(result.getClientCapturedAt()).isEqualTo(NOW.minusSeconds(10));
    }
    @Test void volunteerTypeComesFromAccountRoles() {
        when(auth.requireActiveAccount("volunteer")).thenReturn(account("volunteer", AccountRole.COMMUNITY_VOLUNTEER));
        var result = service.create("volunteer", new ReportContracts.CreateDraftRequest(HazardType.BLOCKED_ROAD, "Tree fallen", null, null, ReportSource.MOBILE_APP));
        assertThat(result.getReporter().type()).isEqualTo(ReporterType.COMMUNITY_VOLUNTEER);
        assertThat(result.getReporter().user().subjectId()).isEqualTo("volunteer");
    }
    @Test void officerCannotUseReporterServiceWithoutReporterRole() {
        when(auth.requireActiveAccount("officer")).thenReturn(account("officer", AccountRole.DUTY_OFFICER));
        assertThatThrownBy(() -> service.detail("officer", "report"))
                .isInstanceOfSatisfying(ReportException.class, e -> assertThat(e.status().value()).isEqualTo(403));
        verifyNoInteractions(reports);
    }
    @Test void anotherUsersReportIsNotExposed() {
        assertThatThrownBy(() -> service.detail("citizen", "someone-elses-report"))
                .isInstanceOfSatisfying(ReportException.class, e -> assertThat(e.status().value()).isEqualTo(404));
        verify(reports).findByIdAndReporterUserSubjectId("someone-elses-report", "citizen");
        verify(reports, never()).findById(anyString());
    }
    @Test void updatesDraftAndReturnsPersistedVersion() {
        var draft = draft(); load(draft);
        var result = service.update("citizen", draft.getId(), new ReportContracts.UpdateDraftRequest(0L,
                HazardType.FLOODING, "Water rising", new ReportContracts.LocationRequest(0.0, 0.0, "Area", NOW)));
        assertThat(result.getVersion()).isEqualTo(1);
        assertThat(result.getLocation().latitude()).isZero();
        assertThat(result.getHistory()).hasSize(2);
    }
    @Test void staleAndInvalidVersionsDoNotSaveOrStore() {
        var draft = draft(); load(draft);
        assertThatThrownBy(() -> service.remove("citizen", draft.getId(), 5)).isInstanceOf(ReportException.class);
        assertThatThrownBy(() -> service.remove("citizen", draft.getId(), -1)).isInstanceOf(ReportException.class);
        verify(reports, never()).save(any()); verifyNoInteractions(storage);
    }
    @Test void missingSubmissionFieldsReturnAllErrorsAndPreserveDraft() {
        var draft = draft(); load(draft);
        assertThatThrownBy(() -> service.submit("citizen", draft.getId(), 0))
                .isInstanceOfSatisfying(ReportException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(422);
                    assertThat(e.fields()).extracting(ReportException.FieldError::field)
                            .containsExactly("hazardType", "description", "location", "photo");
                });
        assertThat(persisted.getStatus()).isEqualTo(ReportStatus.DRAFT);
        verify(reports, never()).save(any());
    }
    @Test void successfulSubmissionChecksEvidenceAndPreservesCaptureTime() {
        var report = complete(); load(report);
        var result = service.submit("citizen", report.getId(), 0);
        verify(storage).read(report.getPhoto());
        assertThat(result.getStatus()).isEqualTo(ReportStatus.SUBMITTED);
        assertThat(result.getSubmittedAt()).isEqualTo(NOW);
        assertThat(result.getClientCapturedAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(result.getVersion()).isEqualTo(1);
    }
    @Test void missingStoredEvidenceLeavesDraftUnsubmitted() {
        var report = complete(); load(report);
        when(storage.read(any())).thenThrow(new ReportException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "EVIDENCE_UNAVAILABLE", "Unavailable"));
        assertThatThrownBy(() -> service.submit("citizen", report.getId(), 0)).isInstanceOf(ReportException.class);
        assertThat(persisted.getStatus()).isEqualTo(ReportStatus.DRAFT);
        verify(reports, never()).save(any());
    }
    @Test void failedSubmissionSaveDoesNotChangePersistedDraft() {
        var report = complete(); load(report);
        doThrow(new DataAccessResourceFailureException("private database details")).when(reports).save(any());
        assertThatThrownBy(() -> service.submit("citizen", report.getId(), 0)).isInstanceOf(DataAccessException.class);
        assertThat(persisted.getStatus()).isEqualTo(ReportStatus.DRAFT);
        assertThat(persisted.getSubmittedAt()).isNull();
        assertThat(persisted.getPhoto()).isEqualTo(report.getPhoto());
    }
    @Test void submittedReportsCannotBeEditedReattachedRemovedOrResubmitted() {
        var report = complete(); report.submit(CLOCK); load(report);
        assertThatThrownBy(() -> service.update("citizen", report.getId(), new ReportContracts.UpdateDraftRequest(0L, null, "Edit", null)))
                .isInstanceOf(ReportException.class);
        assertThatThrownBy(() -> service.attach("citizen", report.getId(), 0, mockFile(), null)).isInstanceOf(ReportException.class);
        assertThatThrownBy(() -> service.remove("citizen", report.getId(), 0)).isInstanceOf(ReportException.class);
        assertThatThrownBy(() -> service.submit("citizen", report.getId(), 0)).isInstanceOf(ReportException.class);
        verify(reports, never()).save(any()); verifyNoInteractions(storage);
    }
    @Test void photoReplacementAndRemovalKeepPreviousPrivateFileForSafeCleanup() {
        var report = complete(); load(report);
        var newPhoto = new PhotoEvidence("new", "new", "new.png", "image/png", 4, "b".repeat(64), report.getReporter().user(), NOW, null);
        when(storage.store(any(), any(), any(), any())).thenReturn(newPhoto);
        var result = service.attach("citizen", report.getId(), 0, mockFile(), null);
        assertThat(result.getPhoto()).isEqualTo(newPhoto);
        var removed = service.remove("citizen", report.getId(), 1);
        assertThat(removed.getPhoto()).isNull(); assertThat(removed.getVersion()).isEqualTo(2);
        verify(storage, never()).discardUnattached(any());
    }
    @Test void optimisticPhotoConflictDiscardsOnlyNewUnattachedFile() {
        var report = complete(); load(report);
        var newPhoto = photo(); when(storage.store(any(), any(), any(), any())).thenReturn(newPhoto);
        doThrow(new OptimisticLockingFailureException("conflict")).when(reports).save(any());
        assertThatThrownBy(() -> service.attach("citizen", report.getId(), 0, mockFile(), null))
                .isInstanceOf(OptimisticLockingFailureException.class);
        verify(storage).discardUnattached(newPhoto);
        assertThat(persisted.getVersion()).isZero();
    }
    @Test void ambiguousPhotoSaveFailureRetainsFileBecauseWriteMayHaveSucceeded() {
        var report = complete(); load(report);
        when(storage.store(any(), any(), any(), any())).thenReturn(photo());
        doThrow(new DataAccessResourceFailureException("timeout")).when(reports).save(any());
        assertThatThrownBy(() -> service.attach("citizen", report.getId(), 0, mockFile(), null)).isInstanceOf(DataAccessException.class);
        verify(storage, never()).discardUnattached(any());
    }
    @Test void paginationIsScopedBoundedAndDeterministicallySorted() {
        when(reports.findByReporterUserSubjectIdAndStatus(eq("citizen"), eq(ReportStatus.DRAFT), any()))
                .thenReturn(new PageImpl<>(List.of(draft())));
        assertThat(service.mine("citizen", 0, 20, ReportStatus.DRAFT).items()).hasSize(1);
        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(reports).findByReporterUserSubjectIdAndStatus(eq("citizen"), eq(ReportStatus.DRAFT), captor.capture());
        assertThat(captor.getValue().getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(captor.getValue().getSort().getOrderFor("id")).isNotNull();
        assertThatThrownBy(() -> service.mine("citizen", -1, 20, null)).isInstanceOf(ReportException.class);
        assertThatThrownBy(() -> service.mine("citizen", 0, 101, null)).isInstanceOf(ReportException.class);
        assertThatThrownBy(() -> service.mine("citizen", 0, 0, null)).isInstanceOf(ReportException.class);
    }
    @Test void photoReadIsOwnerScopedAndMissingPhotoIsNotFound() {
        var report = draft(); load(report);
        assertThatThrownBy(() -> service.photo("citizen", report.getId())).isInstanceOf(ReportException.class);
        verifyNoInteractions(storage);
    }
    private MockMultipartFile mockFile() { return new MockMultipartFile("file", "road.png", "image/png", new byte[]{1}); }
}
