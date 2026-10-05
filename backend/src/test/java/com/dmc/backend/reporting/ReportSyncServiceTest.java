package com.dmc.backend.reporting;

import static com.dmc.backend.reporting.ReportFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.dmc.backend.auth.*;
import com.dmc.backend.models.hazard.*;
import com.dmc.backend.models.hazard.HazardReport.*;
import com.dmc.backend.repository.HazardReportRepository;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.*;
import org.springframework.mock.web.MockMultipartFile;

class ReportSyncServiceTest {
    @TempDir Path directory;
    HazardReportRepository reports;
    AuthService auth;
    EvidenceStorage evidence;
    ReportSyncService service;
    Map<String,HazardReport> saved;
    @BeforeEach void setup() {
        reports = mock(HazardReportRepository.class); auth = mock(AuthService.class);
        evidence = spy(new LocalEvidenceStorage(directory.toString()));
        service = new ReportSyncService(auth, reports, evidence, CLOCK);
        saved = new HashMap<>();
        when(auth.requireActiveAccount(anyString())).thenAnswer(call -> account(call.getArgument(0), AccountRole.CITIZEN));
        when(reports.findByIdAndReporterUserSubjectId(anyString(), anyString())).thenAnswer(call -> {
            HazardReport report = saved.get(call.getArgument(0));
            return report == null || !report.getReporter().user().subjectId().equals(call.getArgument(1))
                    ? Optional.empty() : Optional.of(copy(report));
        });
        when(reports.save(any())).thenAnswer(call -> {
            HazardReport report = version(call.getArgument(0), 0);
            saved.put(report.getId(), report); return copy(report);
        });
    }
    ReportSyncService.SyncRequest request(String description) {
        return new ReportSyncService.SyncRequest(HazardType.FLOODING, description,
                new ReportContracts.LocationRequest(0.0, 0.0, "District", NOW.minusSeconds(120)),
                NOW.minusSeconds(180), NOW.minusSeconds(150), ReportSource.MOBILE_APP);
    }
    MockMultipartFile photoFile() throws Exception {
        return new MockMultipartFile("file", "road.png", "image/png", LocalEvidenceStorageTest.image("png", 3, 3));
    }
    @Test void repeatedSubmissionUsesOriginalReportAndDoesNotDuplicatePhotoOrHistory() throws Exception {
        var first = service.synchronize("citizen", "local-report-1", request("Water rising"), photoFile());
        var retry = service.synchronize("citizen", "local-report-1", request("Water rising"), photoFile());
        assertThat(retry.getId()).isEqualTo(first.getId()); assertThat(retry.getReference()).isEqualTo(first.getReference());
        assertThat(retry.getHistory()).isEqualTo(first.getHistory());
        assertThat(retry.getStatus()).isEqualTo(ReportStatus.SUBMITTED);
        assertThat(retry.getClientCapturedAt()).isEqualTo(NOW.minusSeconds(180));
        assertThat(retry.getPhoto().capturedAt()).isEqualTo(NOW.minusSeconds(150));
        assertThat(retry.getSubmittedAt()).isEqualTo(NOW);
        verify(evidence, times(1)).store(any(), any(), any(), any()); verify(reports, times(1)).save(any());
    }
    @Test void sameKeyWithChangedPayloadConflictsAndDifferentReporterIsIndependent() throws Exception {
        var first = service.synchronize("citizen", "key", request("First payload"), photoFile());
        assertThatThrownBy(() -> service.synchronize("citizen", "key", request("Changed payload"), photoFile()))
                .isInstanceOfSatisfying(ReportException.class, e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        var second = service.synchronize("other-citizen", "key", request("First payload"), photoFile());
        assertThat(second.getId()).isNotEqualTo(first.getId()); assertThat(saved).hasSize(2);
    }
    @Test void volunteerSourceAndTypeComeFromSupportedIdentityAndMetadata() throws Exception {
        when(auth.requireActiveAccount("volunteer")).thenReturn(account("volunteer", AccountRole.COMMUNITY_VOLUNTEER));
        var result = service.synchronize("volunteer", "key", request("Observed hazard"), photoFile());
        assertThat(result.getReporter().type()).isEqualTo(ReporterType.COMMUNITY_VOLUNTEER);
        assertThat(result.getSource()).isEqualTo(ReportSource.MOBILE_APP);
    }
    @Test void duplicateInsertRaceReturnsWinnerAndDiscardsOnlyLosingAttachment() throws Exception {
        doAnswer(call -> {
            HazardReport attempted = call.getArgument(0);
            var winner = HazardReport.synchronizedDraft(attempted.getReporter(), attempted.getSource(),
                    attempted.getClientCapturedAt(), attempted.getId(), attempted.getSyncDigest(), CLOCK);
            winner.updateDraft(attempted.getHazardType(), attempted.getDescription(), attempted.getLocation(), CLOCK);
            winner.replacePhoto(evidence.store(photoFile(), attempted.getReporter().user(),
                    attempted.getPhoto().capturedAt(), NOW), CLOCK);
            winner.submit(CLOCK);
            saved.put(winner.getId(), version(winner, 0));
            throw new DuplicateKeyException("competing insert");
        }).when(reports).save(any());
        var result = service.synchronize("citizen", "key", request("Hazard"), photoFile());
        assertThat(result.getStatus()).isEqualTo(ReportStatus.SUBMITTED);
        verify(evidence).discardUnattached(any()); verify(reports, times(1)).save(any());
        assertThat(evidence.read(result.getPhoto())).isNotEmpty();
        assertThat(saved).hasSize(1);
    }
    @Test void unknownSaveOutcomeRetainsEvidenceAndReportsFailure() throws Exception {
        doThrow(new DataAccessResourceFailureException("private details")).when(reports).save(any());
        assertThatThrownBy(() -> service.synchronize("citizen", "key", request("Hazard"), photoFile())).isInstanceOf(DataAccessException.class);
        verify(evidence, never()).discardUnattached(any()); assertThat(saved).isEmpty();
    }
    @Test void invalidKeyRoleAndPhotoNeverSaveReport() throws Exception {
        assertThatThrownBy(() -> service.synchronize("citizen", "bad key", request("Hazard"), photoFile())).isInstanceOf(ReportException.class);
        assertThatThrownBy(() -> service.synchronize("citizen", "key", request("Hazard"),
                new MockMultipartFile("file", "fake.png", "image/png", new byte[]{1,2,3}))).isInstanceOf(ReportException.class);
        when(auth.requireActiveAccount("officer")).thenReturn(account("officer", AccountRole.DUTY_OFFICER));
        assertThatThrownBy(() -> service.synchronize("officer", "key", request("Hazard"), photoFile())).isInstanceOf(ReportException.class);
        verify(reports, never()).save(any());
    }
    @Test void retryResolvesWriteThatSucceededBeforeNetworkFailure() throws Exception {
        doAnswer(call -> {
            HazardReport report=version(call.getArgument(0),0);saved.put(report.getId(),report);
            throw new DataAccessResourceFailureException("acknowledgement lost");
        }).when(reports).save(any());
        assertThatThrownBy(() -> service.synchronize("citizen","uncertain",request("Hazard"),photoFile())).isInstanceOf(DataAccessException.class);
        var retried=service.synchronize("citizen","uncertain",request("Hazard"),photoFile());
        assertThat(retried.getStatus()).isEqualTo(ReportStatus.SUBMITTED);
        assertThat(evidence.read(retried.getPhoto())).isNotEmpty();
        verify(reports,times(1)).save(any());verify(evidence,times(1)).store(any(),any(),any(),any());
    }
    @Test void retryAfterOfficerReviewReturnsCurrentStateWithoutRecreatingReport() throws Exception {
        var report=service.synchronize("citizen","reviewed",request("Hazard"),photoFile());
        var stored=saved.get(report.getId());stored.startReview(new UserReference("officer","Officer"),CLOCK);
        var retry=service.synchronize("citizen","reviewed",request("Hazard"),photoFile());
        assertThat(retry.getStatus()).isEqualTo(ReportStatus.UNDER_REVIEW);assertThat(retry.getReference()).isEqualTo(report.getReference());
        verify(reports,times(1)).save(any());
    }
    @Test void emptyOversizedAndUnreadableUploadsCannotSave() throws Exception {
        assertThatThrownBy(() -> service.synchronize("citizen","empty",request("Hazard"),new MockMultipartFile("file",new byte[0])))
                .isInstanceOf(ReportException.class);
        assertThatThrownBy(() -> service.synchronize("citizen","big",request("Hazard"),new MockMultipartFile("file",new byte[5*1024*1024+1])))
                .isInstanceOfSatisfying(ReportException.class,e -> assertThat(e.status().value()).isEqualTo(413));
        var unreadable=mock(org.springframework.web.multipart.MultipartFile.class);when(unreadable.isEmpty()).thenReturn(false);
        when(unreadable.getSize()).thenReturn(1L);when(unreadable.getInputStream()).thenThrow(new java.io.IOException("private path"));
        assertThatThrownBy(() -> service.synchronize("citizen","unreadable",request("Hazard"),unreadable))
                .isInstanceOfSatisfying(ReportException.class,e -> assertThat(e.status().value()).isEqualTo(503));
        verify(reports,never()).save(any());
    }
    @Test void absentSourceDefaultsToWebPortalAndMillisecondsRemainStableOnRetry() throws Exception {
        var location=new ReportContracts.LocationRequest(0.0,0.0,null,null);
        var request=new ReportSyncService.SyncRequest(HazardType.FLOODING,"Hazard",location,NOW.plusNanos(100),null,null);
        var first=service.synchronize("citizen","default-source",request,photoFile());
        var retryRequest=new ReportSyncService.SyncRequest(HazardType.FLOODING,"Hazard",location,NOW.plusNanos(900),null,ReportSource.WEB_PORTAL);
        var retry=service.synchronize("citizen","default-source",retryRequest,photoFile());
        assertThat(first.getSource()).isEqualTo(ReportSource.WEB_PORTAL);assertThat(retry.getId()).isEqualTo(first.getId());
    }
}
