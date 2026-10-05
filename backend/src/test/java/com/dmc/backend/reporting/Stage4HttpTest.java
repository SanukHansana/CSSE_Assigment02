package com.dmc.backend.reporting;

import static com.dmc.backend.reporting.ReportFixtures.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.dmc.backend.auth.*;
import com.dmc.backend.models.hazard.*;
import com.dmc.backend.models.hazard.HazardReport.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"spring.config.import=","spring.mongodb.uri=mongodb://localhost:27017/dmc_test",
        "dmc.auth.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","logging.level.org.mongodb.driver=OFF"})
@AutoConfigureMockMvc
class Stage4HttpTest {
    @Autowired MockMvc mvc;@Autowired JwtEncoder encoder;
    @MockitoBean UserAccountRepository users;@MockitoBean ReportSyncService sync;@MockitoBean VerifiedEvidenceService evidence;
    String bearer;
    @BeforeEach void setup() {
        when(users.findById("user")).thenReturn(Optional.of(account("user",AccountRole.CITIZEN)));
        var claims=JwtClaimsSet.builder().subject("user").issuer("dmc-backend").audience(List.of("dmc-api"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).build();
        bearer="Bearer "+encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
    }
    MockMultipartFile json(String value) {return new MockMultipartFile("report","report.json","application/json",value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    MockMultipartFile file() {return new MockMultipartFile("file","road.png","image/png",new byte[]{1,2,3});}
    @Test void syncValidatesFieldsAndHeaderAndRequiresReporterAuthentication() throws Exception {
        String request="{\"hazardType\":\"FLOODING\",\"description\":\"Road flooded\",\"location\":{\"latitude\":0,\"longitude\":0}}";
        var submitted=complete();submitted.submit(CLOCK);when(sync.synchronize(eq("user"),eq("local-1"),any(),any())).thenReturn(submitted);
        mvc.perform(multipart("/api/dmc/ground-reports/sync").file(json(request)).file(file()).header("Authorization",bearer).header("Idempotency-Key","local-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED")).andExpect(jsonPath("$.syncDigest").doesNotExist());
        mvc.perform(multipart("/api/dmc/ground-reports/sync").file(json("{}")).file(file()).header("Authorization",bearer).header("Idempotency-Key","local-2"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/dmc/ground-reports/sync").file(json(request)).file(file()).header("Authorization",bearer)).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/dmc/ground-reports/sync").file(json(request)).file(file()).header("Idempotency-Key","local-1")).andExpect(status().isUnauthorized());
        when(users.findById("user")).thenReturn(Optional.of(account("user",AccountRole.DMC_OFFICER)));
        mvc.perform(multipart("/api/dmc/ground-reports/sync").file(json(request)).file(file()).header("Authorization",bearer).header("Idempotency-Key","local-1"))
                .andExpect(status().isForbidden());
    }
    @Test void syncPayloadConflictUsesStructuredError() throws Exception {
        when(sync.synchronize(anyString(),anyString(),any(),any())).thenThrow(new ReportException(org.springframework.http.HttpStatus.CONFLICT,
                "IDEMPOTENCY_KEY_REUSED","Different submission"));
        mvc.perform(multipart("/api/dmc/ground-reports/sync").file(json("{\"hazardType\":\"FLOODING\",\"description\":\"Road flooded\",\"location\":{\"latitude\":0,\"longitude\":0}}"))
                .file(file()).header("Authorization",bearer).header("Idempotency-Key","key"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }
    @Test void verifiedEvidenceRequiresDmcRoleAndReturnsSafeDecisionData() throws Exception {
        mvc.perform(get("/api/dmc/ground-reports/verified-evidence").header("Authorization",bearer)).andExpect(status().isForbidden());
        when(users.findById("user")).thenReturn(Optional.of(account("user",AccountRole.DMC_OFFICER)));
        var verified=complete();verified.submit(CLOCK);var officer=new UserReference("officer","Officer");verified.startReview(officer,CLOCK);
        verified.updateReview(officer,new CredibilityChecklist(true,true,true,true),"Credible",CLOCK);verified.decide(officer,VerificationDecision.VERIFIED,null,CLOCK);
        when(evidence.detail("user",verified.getId())).thenReturn(verified);
        mvc.perform(get("/api/dmc/ground-reports/verified-evidence/"+verified.getId()).header("Authorization",bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verification.decision").value("VERIFIED"))
                .andExpect(jsonPath("$.photo.storageKey").doesNotExist()).andExpect(jsonPath("$.relatedAssessmentReference").doesNotExist());
    }
    @Test void verifiedListAndPhotoRoutesUseProtectedAssessmentProjection() throws Exception {
        when(users.findById("user")).thenReturn(Optional.of(account("user",AccountRole.DMC_OFFICER)));
        var verified=complete();verified.submit(CLOCK);var officer=new UserReference("officer","Officer");verified.startReview(officer,CLOCK);
        verified.updateReview(officer,new CredibilityChecklist(true,true,true,true),"Credible",CLOCK);verified.decide(officer,VerificationDecision.VERIFIED,null,CLOCK);
        when(evidence.list("user",null,0,20)).thenReturn(List.of(ReportContracts.ReportResponse.fromForAssessment(verified)));
        mvc.perform(get("/api/dmc/ground-reports/verified-evidence").header("Authorization",bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("VERIFIED"));
        when(evidence.photo("user",verified.getId())).thenReturn(new GroundReportService.PhotoContent(verified.getPhoto(),new byte[]{1,2,3}));
        String path="/api/dmc/ground-reports/"+verified.getId()+"/assessment/photo";
        mvc.perform(get(path).header("Authorization",bearer)).andExpect(status().isOk())
                .andExpect(content().contentType("image/png")).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get(path+"/download").header("Authorization",bearer)).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.startsWith("attachment")));
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }
}
