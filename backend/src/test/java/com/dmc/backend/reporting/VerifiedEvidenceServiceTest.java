package com.dmc.backend.reporting;

import static com.dmc.backend.reporting.ReportFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.dmc.backend.auth.*;
import com.dmc.backend.models.hazard.*;
import com.dmc.backend.models.hazard.HazardReport.*;
import com.dmc.backend.repository.HazardReportRepository;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class VerifiedEvidenceServiceTest {
    AuthService auth; HazardReportRepository reports; EvidenceStorage evidence; MongoTemplate mongo; VerifiedEvidenceService service;
    @BeforeEach void setup() {
        auth=mock(AuthService.class); reports=mock(HazardReportRepository.class); evidence=mock(EvidenceStorage.class); mongo=mock(MongoTemplate.class);
        service=new VerifiedEvidenceService(auth,reports,evidence,mongo);
        when(auth.requireActiveAccount("dmc")).thenReturn(account("dmc",AccountRole.DMC_OFFICER));
    }
    HazardReport decided(VerificationDecision decision) {
        var report=complete(); report.submit(CLOCK); var officer=new UserReference("officer","Officer"); report.startReview(officer,CLOCK);
        report.updateReview(officer,new CredibilityChecklist(true,true,true,true),"Credible",CLOCK);
        report.decide(officer,decision,decision==VerificationDecision.REJECTED?"Unrelated evidence":null,CLOCK); return report;
    }
    @Test void onlyVerifiedReportsCanBeReadOrUsedAsPhotoEvidence() {
        for (var report : List.of(draft(),complete(),decided(VerificationDecision.REJECTED))) {
            when(reports.findById(report.getId())).thenReturn(Optional.of(report));
            assertThatThrownBy(() -> service.detail("dmc",report.getId())).isInstanceOf(ReportException.class);
            assertThatThrownBy(() -> service.photo("dmc",report.getId())).isInstanceOf(ReportException.class);
        }
        var submitted=complete();submitted.submit(CLOCK);when(reports.findById(submitted.getId())).thenReturn(Optional.of(submitted));
        assertThatThrownBy(() -> service.detail("dmc",submitted.getId())).isInstanceOf(ReportException.class);
        submitted.startReview(new UserReference("officer","Officer"),CLOCK);
        assertThatThrownBy(() -> service.detail("dmc",submitted.getId())).isInstanceOf(ReportException.class);
        verifyNoInteractions(evidence);
        var verified=decided(VerificationDecision.VERIFIED);when(reports.findById(verified.getId())).thenReturn(Optional.of(verified));
        when(evidence.read(any())).thenReturn(new byte[]{1,2,3});
        assertThat(service.detail("dmc",verified.getId()).isEligibleForAssessment()).isTrue();
        assertThat(service.photo("dmc",verified.getId()).bytes()).hasSize(3);
    }
    @Test void listFiltersIneligibleRecordsAndProvidesProtectedAssessmentPhotoUrls() {
        var verified=decided(VerificationDecision.VERIFIED);
        when(mongo.find(any(Query.class),eq(HazardReport.class))).thenReturn(List.of(verified,decided(VerificationDecision.REJECTED)));
        var result=service.list("dmc",HazardType.FLOODING,0,20);
        assertThat(result).hasSize(1);assertThat(result.getFirst().verification().decision()).isEqualTo(VerificationDecision.VERIFIED);
        assertThat(result.getFirst().photo().viewUrl()).endsWith("/assessment/photo");
    }
    @Test void citizenAndDutyOfficerCannotUseOfficialEvidenceBoundary() {
        for (var role:List.of(AccountRole.CITIZEN,AccountRole.DUTY_OFFICER)) {
            when(auth.requireActiveAccount("other")).thenReturn(account("other",role));
            assertThatThrownBy(() -> service.list("other",null,0,20)).isInstanceOf(ReportException.class);
        }
        verifyNoInteractions(mongo,reports);
        assertThatThrownBy(() -> service.list("dmc",null,-1,20)).isInstanceOf(ReportException.class);
        assertThatThrownBy(() -> service.list("dmc",null,0,101)).isInstanceOf(ReportException.class);
    }
}
