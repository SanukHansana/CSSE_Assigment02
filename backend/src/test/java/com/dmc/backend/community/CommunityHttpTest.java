package com.dmc.backend.community;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dmc.backend.auth.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
  properties = {
    "spring.config.import=",
    "spring.mongodb.uri=mongodb://localhost:27017/dmc_test",
    "dmc.auth.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
  }
)
@AutoConfigureMockMvc
class CommunityHttpTest {

  @Autowired
  MockMvc mvc;

  @Autowired
  JwtEncoder encoder;

  @MockitoBean
  CommunityService service;

  @MockitoBean
  AuthService auth;

  @MockitoBean
  UserAccountRepository users;

  final String base = "/api/dmc/community/needs";
  String token;

  @BeforeEach
  void setup() {
    role(AccountRole.CITIZEN, "APPROVED");
    var now = Instant.now();
    token = encoder
      .encode(
        JwtEncoderParameters.from(
          JwsHeader.with(MacAlgorithm.HS256).build(),
          JwtClaimsSet.builder()
            .subject("user")
            .issuer("dmc-backend")
            .audience(List.of("dmc-api"))
            .issuedAt(now)
            .expiresAt(now.plusSeconds(900))
            .build()
        )
      )
      .getTokenValue();
  }

  void role(AccountRole role, String approval) {
    var account = new UserAccount(
      "user",
      "user@example.test",
      "User",
      "hash",
      Set.of(role),
      true,
      Instant.now(),
      role,
      approval,
      null,
      null,
      null
    );
    when(users.findById("user")).thenReturn(Optional.of(account));
    when(auth.requireActiveAccount("user")).thenReturn(account);
  }

  @Test
  void authenticatedCitizenCreatesAndReadsWithoutPrivateFields()
    throws Exception {
    when(service.list("user", "mine", 0, 10)).thenReturn(
      new CommunityService.Page(List.of(), 0, 10, 0, 0)
    );
    mvc
      .perform(get(base).header("Authorization", "Bearer " + token))
      .andExpect(status().isOk())
      .andExpect(header().string("Cache-Control", "no-store"))
      .andExpect(jsonPath("$.items").isArray());
    mvc
      .perform(
        post(base)
          .header("Authorization", "Bearer " + token)
          .contentType("application/json")
          .content(
            "{\"item\":\"Water\",\"category\":\"WATER\",\"quantity\":10,\"unit\":\"bottles\",\"description\":\"Drinking water\",\"area\":\"Colombo\"}"
          )
      )
      .andExpect(status().isCreated());
    verify(service).create(eq("user"), argThat(body -> body.quantity() == 10));
  }

  @Test
  void officerPendingVolunteerAndAnonymousCannotCreateCitizenNeeds()
    throws Exception {
    mvc.perform(get(base)).andExpect(status().isUnauthorized());
    for (var role : List.of(
      AccountRole.DMC_OFFICER,
      AccountRole.DUTY_OFFICER,
      AccountRole.ADMIN,
      AccountRole.COMMUNITY_VOLUNTEER
    )) {
      role(role, "APPROVED");
      mvc
        .perform(
          post(base)
            .header("Authorization", "Bearer " + token)
            .contentType("application/json")
            .content(
              "{\"item\":\"Water\",\"category\":\"WATER\",\"quantity\":10,\"unit\":\"bottles\",\"description\":\"Need\",\"area\":\"Colombo\"}"
            )
        )
        .andExpect(status().isForbidden());
    }
    role(AccountRole.CITIZEN, "PENDING");
    mvc
      .perform(get(base).header("Authorization", "Bearer " + token))
      .andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }

  @Test
  void invalidJsonBoundsAndTypesAreReadableAndNeverMutate() throws Exception {
    for (String body : List.of(
      "{",
      "{}",
      "{\"item\":\"Water\",\"category\":\"WATER\",\"quantity\":0,\"unit\":\"bottles\",\"description\":\"Need\",\"area\":\"Colombo\"}"
    )) {
      mvc
        .perform(
          post(base)
            .header("Authorization", "Bearer " + token)
            .contentType("application/json")
            .content(body)
        )
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
    mvc
      .perform(
        get(base)
          .param("page", "bad")
          .header("Authorization", "Bearer " + token)
      )
      .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test
  void offerActionCancellationAndDetailRoutesForwardAuthenticatedSubject()
    throws Exception {
    mvc
      .perform(get(base + "/need").header("Authorization", "Bearer " + token))
      .andExpect(status().isOk());
    verify(service).detail("user", "need");
    mvc
      .perform(
        post(base + "/need/offers")
          .header("Authorization", "Bearer " + token)
          .contentType("application/json")
          .content(
            "{\"requestId\":\"offer\",\"expectedVersion\":0,\"quantity\":2,\"note\":\"My bottles\"}"
          )
      )
      .andExpect(status().isOk());
    verify(service).offer(eq("user"), eq("need"), any());
    mvc
      .perform(
        post(base + "/need/offers/offer/action")
          .header("Authorization", "Bearer " + token)
          .contentType("application/json")
          .content(
            "{\"requestId\":\"accept\",\"expectedVersion\":1,\"action\":\"ACCEPT\"}"
          )
      )
      .andExpect(status().isOk());
    verify(service).act(eq("user"), eq("need"), eq("offer"), any());
    mvc
      .perform(
        post(base + "/need/cancel")
          .header("Authorization", "Bearer " + token)
          .contentType("application/json")
          .content("{\"requestId\":\"cancel\",\"expectedVersion\":2}")
      )
      .andExpect(status().isOk());
    verify(service).cancel(eq("user"), eq("need"), any());
  }

  @ParameterizedTest
  @ValueSource(ints = { 404, 409 })
  void domainErrorsRetainStatus(int status) throws Exception {
    when(service.detail("user", "need")).thenThrow(
      new ResponseStatusException(
        HttpStatus.valueOf(status),
        "Request rejected"
      )
    );
    mvc
      .perform(get(base + "/need").header("Authorization", "Bearer " + token))
      .andExpect(status().is(status))
      .andExpect(jsonPath("$.message").value("Request rejected"));
  }

  @Test
  void concurrentWritesAndDatabaseFailureReturnRetryableErrors()
    throws Exception {
    when(service.detail("user", "need")).thenThrow(
      new OptimisticLockingFailureException("Changed")
    );
    mvc
      .perform(get(base + "/need").header("Authorization", "Bearer " + token))
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    doThrow(new DataAccessResourceFailureException("Offline"))
      .when(service)
      .detail("user", "need");
    mvc
      .perform(get(base + "/need").header("Authorization", "Bearer " + token))
      .andExpect(status().isServiceUnavailable())
      .andExpect(jsonPath("$.code").value("COMMUNITY_UNAVAILABLE"));
  }
}
