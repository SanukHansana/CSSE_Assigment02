package com.dmc.backend.reporting;

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
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"spring.config.import=", "spring.mongodb.uri=mongodb://localhost:27017/dmc_test",
        "dmc.auth.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "logging.level.org.mongodb.driver=OFF"})
@AutoConfigureMockMvc
class GroundReportHttpTest {
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @MockitoBean AuthService auth;
    @MockitoBean UserAccountRepository users;
    @MockitoBean HazardReportRepository reports;
    @MockitoBean EvidenceStorage evidence;
    HazardReport persisted;
    String token;

    @BeforeEach void setup() {
        var user = account("citizen", AccountRole.CITIZEN);
        when(users.findById("citizen")).thenReturn(Optional.of(user));
        when(auth.requireActiveAccount("citizen")).thenReturn(user);
        token = token("citizen");
        persisted = draft();
        when(reports.findByIdAndReporterUserSubjectId(anyString(), eq("citizen")))
                .thenAnswer(call -> call.getArgument(0).equals(persisted.getId()) ? Optional.of(copy(persisted)) : Optional.empty());
        when(reports.save(any())).thenAnswer(call -> {
            HazardReport value = call.getArgument(0);
            persisted = version(value, value.getVersion() == null ? 0 : value.getVersion() + 1);
            return copy(persisted);
        });
    }
    @Test void requiresAuthenticationAndReporterRole() throws Exception {
        mvc.perform(post("/api/dmc/ground-reports/drafts").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        when(users.findById("citizen")).thenReturn(Optional.of(account("citizen", AccountRole.DUTY_OFFICER)));
        mvc.perform(post("/api/dmc/ground-reports/drafts").header("Authorization", bearer()).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        verify(reports, never()).save(any());
    }
    @Test void createsDraftWithServerIdentityEvenIfCallerSuppliesIdentityAndStatus() throws Exception {
        mvc.perform(post("/api/dmc/ground-reports/drafts").header("Authorization", bearer()).contentType("application/json")
                .content("{\"reporterId\":\"another-user\",\"status\":\"VERIFIED\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.reporter.displayName").value("Reporter citizen"))
                .andExpect(jsonPath("$.reporter.type").value("CITIZEN"))
                .andExpect(jsonPath("$.version").value(0)).andExpect(header().string("Cache-Control", "no-store"));
    }
    @Test void missingLocationCoordinateAndOversizedDescriptionAreRejected() throws Exception {
        mvc.perform(post("/api/dmc/ground-reports/drafts").header("Authorization", bearer()).contentType("application/json")
                .content("{\"location\":{\"longitude\":0}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("location.latitude"));
        mvc.perform(post("/api/dmc/ground-reports/drafts").header("Authorization", bearer()).contentType("application/json")
                .content("{\"description\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());
        verify(reports, never()).save(any());
    }
    @Test void invalidEnumsCoordinatesAndMalformedJsonAreSafeErrors() throws Exception {
        for (String json : List.of("{", "{\"hazardType\":\"UNKNOWN\"}", "{\"location\":{\"latitude\":91,\"longitude\":0}}")) {
            mvc.perform(post("/api/dmc/ground-reports/drafts").header("Authorization", bearer()).contentType("application/json").content(json))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }
    @Test void detailsAreReadOnlyAndOtherOwnersAreNotFound() throws Exception {
        mvc.perform(get(path()).header("Authorization", bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.history[0].type").value("DRAFT_CREATED"));
        mvc.perform(get("/api/dmc/ground-reports/not-owned").header("Authorization", bearer())).andExpect(status().isNotFound());
        verify(reports, never()).save(any());
    }
    @Test void submissionReturnsRequiredFieldErrorsAndRejectsMissingVersion() throws Exception {
        mvc.perform(post(path() + "/submit").header("Authorization", bearer()).contentType("application/json").content("{\"expectedVersion\":0}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.fieldErrors.length()").value(4));
        mvc.perform(post(path() + "/submit").header("Authorization", bearer()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
    }
    @Test void draftUpdateAndStaleVersionHaveDifferentResults() throws Exception {
        mvc.perform(patch(path() + "/draft").header("Authorization", bearer()).contentType("application/json")
                .content("{\"expectedVersion\":0,\"description\":\"Water rising\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(patch(path() + "/draft").header("Authorization", bearer()).contentType("application/json")
                .content("{\"expectedVersion\":0,\"description\":\"Stale edit\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REPORT_VERSION_CONFLICT"));
    }
    @Test void databaseErrorsDoNotExposeDetailsOrAcknowledgeSuccess() throws Exception {
        doThrow(new DataAccessResourceFailureException("mongodb://secret/private")).when(reports).save(any());
        mvc.perform(patch(path() + "/draft").header("Authorization", bearer()).contentType("application/json")
                .content("{\"expectedVersion\":0,\"description\":\"Water rising\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("REPORTS_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }
    @Test void optimisticSaveFailureReturnsConflict() throws Exception {
        doThrow(new OptimisticLockingFailureException("private error")).when(reports).save(any());
        mvc.perform(patch(path() + "/draft").header("Authorization", bearer()).contentType("application/json")
                .content("{\"expectedVersion\":0,\"description\":\"Water rising\"}"))
                .andExpect(status().isConflict());
    }
    @Test void photoUploadUsesIfMatchAndNeverReturnsStorageKeyOrHash() throws Exception {
        when(evidence.store(any(), any(), any(), any())).thenReturn(photo());
        mvc.perform(multipart(HttpMethod.PUT, path() + "/photo")
                .file(new MockMultipartFile("file", "road.png", "image/png", new byte[]{1,2,3}))
                .header("Authorization", bearer()).header("If-Match", "\"0\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.photo.originalFilename").value("road.png"))
                .andExpect(jsonPath("$.photo.storageKey").doesNotExist()).andExpect(jsonPath("$.photo.sha256").doesNotExist())
                .andExpect(jsonPath("$.photo.uploadedBy").doesNotExist());
    }
    @Test void photoViewingAndDownloadRemainProtected() throws Exception {
        persisted = complete(); when(evidence.read(any())).thenReturn(new byte[]{1,2,3});
        mvc.perform(get(path() + "/photo").header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline")))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(path() + "/photo/download").header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")));
        mvc.perform(get(path() + "/photo")).andExpect(status().isUnauthorized());
    }
    @Test void invalidMissingAndWildcardIfMatchAreRejected() throws Exception {
        for (String header : List.of("0", "*", "\"-1\"", "\"999999999999999999999999999\"")) {
            mvc.perform(delete(path() + "/photo").header("Authorization", bearer()).header("If-Match", header))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(delete(path() + "/photo").header("Authorization", bearer())).andExpect(status().isBadRequest());
    }
    @Test void successfulSubmissionLocksFutureDraftEdits() throws Exception {
        persisted = complete(); when(evidence.read(any())).thenReturn(new byte[]{1,2,3});
        mvc.perform(post(path() + "/submit").header("Authorization", bearer()).contentType("application/json").content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"));
        mvc.perform(delete(path() + "/photo").header("Authorization", bearer()).header("If-Match", "\"1\""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REPORT_NOT_EDITABLE"));
    }
    private String path() { return "/api/dmc/ground-reports/" + persisted.getId(); }
    private String bearer() { return "Bearer " + token; }
    private String token(String subject) {
        var claims = JwtClaimsSet.builder().subject(subject).issuer("dmc-backend").audience(List.of("dmc-api"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
