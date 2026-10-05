package com.dmc.backend.reporting;

import com.dmc.backend.models.hazard.HazardReport.UserReference;
import static com.dmc.backend.reporting.ReportFixtures.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dmc.backend.auth.*;
import com.dmc.backend.models.hazard.*;
import com.dmc.backend.repository.HazardReportRepository;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"spring.config.import=", "spring.mongodb.uri=mongodb://localhost:27017/dmc_test",
        "dmc.auth.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "logging.level.org.mongodb.driver=OFF"})
@AutoConfigureMockMvc
class OfficerReviewHttpTest {
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @MockitoBean AuthService auth;
    @MockitoBean UserAccountRepository users;
    @MockitoBean HazardReportRepository reports;
    @MockitoSpyBean MongoTemplate mongo;
    @MockitoBean EvidenceStorage evidence;
    HazardReport persisted;
    String bearer;
    final String passed = "{\"descriptionSufficientlyDetailed\":true,\"photoRelevant\":true,\"gpsCorrespondsToArea\":true,\"reportingTimeReasonable\":true}";
    @BeforeEach void setup() {
        var officer = account("officer", AccountRole.DUTY_OFFICER);
        when(users.findById("officer")).thenReturn(Optional.of(officer));
        when(auth.requireActiveAccount("officer")).thenReturn(officer);
        var claims = JwtClaimsSet.builder().subject("officer").issuer("dmc-backend").audience(List.of("dmc-api"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).build();
        bearer = "Bearer " + encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        persisted = complete(); persisted.submit(CLOCK);
        when(reports.findById(anyString())).thenAnswer(call -> call.getArgument(0).equals(persisted.getId())
                ? Optional.of(copy(persisted)) : Optional.empty());
        when(reports.save(any())).thenAnswer(call -> {
            HazardReport value = call.getArgument(0); persisted = version(value, value.getVersion() + 1); return copy(persisted);
        });
    }
    void reviewing() { persisted.startReview(new UserReference("officer", "Reporter officer"), CLOCK); }
    String path() { return "/api/dmc/ground-reports/" + persisted.getId(); }
    @Test void queueRequiresOfficerAndReturnsPaginatedFilteredResults() throws Exception {
        mvc.perform(get("/api/dmc/ground-reports/review-queue")).andExpect(status().isUnauthorized());
        doReturn(List.of(copy(persisted))).when(mongo).find(any(Query.class), eq(HazardReport.class));
        doReturn(1L).when(mongo).count(any(Query.class), eq(HazardReport.class));
        mvc.perform(get("/api/dmc/ground-reports/review-queue").header("Authorization", bearer).param("hazardType", "FLOODING"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("SUBMITTED"))
                .andExpect(jsonPath("$.items[0].photo.viewUrl").value(path() + "/review/photo"))
                .andExpect(jsonPath("$.totalElements").value(1));
        when(users.findById("officer")).thenReturn(Optional.of(account("officer", AccountRole.CITIZEN)));
        mvc.perform(get("/api/dmc/ground-reports/review-queue").header("Authorization", bearer)).andExpect(status().isForbidden());
    }
    @Test void officerDetailIsReadOnlyAndHidesPrivateDraftsAndPhotoMetadata() throws Exception {
        mvc.perform(get(path() + "/review/details").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.photo.storageKey").doesNotExist()).andExpect(jsonPath("$.photo.sha256").doesNotExist());
        verify(reports, never()).save(any());
        persisted = draft();
        mvc.perform(get(path() + "/review/details").header("Authorization", bearer)).andExpect(status().isNotFound());
    }
    @Test void startsReviewAndSavesChecklistWithAuthenticatedOfficer() throws Exception {
        mvc.perform(post(path() + "/review/start").header("Authorization", bearer).contentType("application/json")
                .content("{\"expectedVersion\":0,\"officerId\":\"forged\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.review.officerDisplayName").value("Reporter officer"));
        mvc.perform(patch(path() + "/review").header("Authorization", bearer).contentType("application/json")
                .content("{\"expectedVersion\":1,\"checklist\":" + passed + ",\"comments\":\"Credible\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.review.comments").value("Credible"))
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
    }
    @Test void verifiesWithEvidenceDetailsAndNoInternalOfficerId() throws Exception {
        reviewing();
        mvc.perform(post(path() + "/decision").header("Authorization", bearer).contentType("application/json")
                .content("{\"expectedVersion\":0,\"decision\":\"VERIFIED\",\"checklist\":" + passed + ",\"comments\":\"Consistent evidence\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.verification.reference").exists()).andExpect(jsonPath("$.verification.decidedAt").exists())
                .andExpect(jsonPath("$.verification.officerDisplayName").value("Reporter officer"))
                .andExpect(jsonPath("$.verification.officer").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store"));
    }
    @Test void rejectionRequiresReasonAndReturnsRejectedDetails() throws Exception {
        reviewing();
        mvc.perform(post(path() + "/decision").header("Authorization", bearer).contentType("application/json")
                .content("{\"expectedVersion\":0,\"decision\":\"REJECTED\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.fieldErrors[0].field").value("rejectionReason"));
        mvc.perform(post(path() + "/decision").header("Authorization", bearer).contentType("application/json")
                .content("{\"expectedVersion\":0,\"decision\":\"REJECTED\",\"rejectionReason\":\"Wrong location\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.verification.rejectionReason").value("Wrong location"));
    }
    @Test void staleAssignedAndSaveConflictsAreClearClientErrors() throws Exception {
        reviewing();
        String body = "{\"expectedVersion\":0,\"decision\":\"REJECTED\",\"rejectionReason\":\"Wrong location\"}";
        doThrow(new OptimisticLockingFailureException("private details")).when(reports).save(any());
        mvc.perform(post(path() + "/decision").header("Authorization", bearer).contentType("application/json").content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REPORT_VERSION_CONFLICT"));
        mvc.perform(post(path() + "/decision").header("Authorization", bearer).contentType("application/json")
                .content(body.replace(":0", ":99"))).andExpect(status().isConflict());
        var another = account("other", AccountRole.DUTY_OFFICER);
        when(auth.requireActiveAccount("officer")).thenReturn(another);
        mvc.perform(post(path() + "/decision").header("Authorization", bearer).contentType("application/json").content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVIEW_ASSIGNED_TO_ANOTHER_OFFICER"));
    }
    @Test void failedDecisionSaveIsUnavailableWithoutFalseSuccess() throws Exception {
        reviewing(); doThrow(new DataAccessResourceFailureException("mongodb://secret")).when(reports).save(any());
        mvc.perform(post(path() + "/decision").header("Authorization", bearer).contentType("application/json")
                .content("{\"expectedVersion\":0,\"decision\":\"VERIFIED\",\"checklist\":" + passed + "}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("REPORTS_UNAVAILABLE"));
        mvc.perform(get(path() + "/review/details").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
    }
    @Test void malformedDecisionsMissingChecklistAndOversizedCommentsAreRejected() throws Exception {
        reviewing();
        for (String body : List.of("{}", "{\"expectedVersion\":0,\"decision\":\"INVALID\"}",
                "{\"expectedVersion\":0,\"checklist\":" + passed + ",\"comments\":\"" + "x".repeat(2001) + "\"}")) {
            mvc.perform(post(path() + "/decision").header("Authorization", bearer).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post(path() + "/decision").header("Authorization", bearer).contentType("application/json")
                .content("{\"expectedVersion\":0,\"decision\":\"VERIFIED\"}"))
                .andExpect(status().isUnprocessableEntity());
    }
    @Test void officerCanReadEvidenceButCannotReadDraftEvidence() throws Exception {
        when(evidence.read(any())).thenReturn(new byte[]{1,2,3});
        mvc.perform(get(path() + "/review/photo").header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(content().contentType("image/png"));
        mvc.perform(get(path() + "/review/photo/download").header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")));
        persisted = draft();
        mvc.perform(get(path() + "/review/photo").header("Authorization", bearer)).andExpect(status().isNotFound());
    }
}
