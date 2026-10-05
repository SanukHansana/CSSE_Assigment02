package com.dmc.backend.auth;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"spring.config.import=", "spring.mongodb.uri=mongodb://localhost:27017/dmc_test", "dmc.auth.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@AutoConfigureMockMvc
class AuthHttpTest {
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @MockitoBean AuthService auth;
    @MockitoBean UserAccountRepository users;

    @Test
    void meRequiresAuthenticationAndMalformedTokensAreRejected() throws Exception {
        mvc.perform(get("/api/dmc/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/dmc/auth/me").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void protectedMeUsesEnabledDatabaseIdentityAndReturnsNoHash() throws Exception {
        var user = account(true);
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(auth.requireActiveAccount(user.id())).thenReturn(user);
        mvc.perform(get("/api/dmc/auth/me").header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("user-1"))
                .andExpect(jsonPath("$.roles[0]").value("CITIZEN"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void disabledAccountIsRejectedEvenWithValidToken() throws Exception {
        when(users.findById("user-1")).thenReturn(Optional.of(account(false)));
        mvc.perform(get("/api/dmc/auth/me").header("Authorization", "Bearer " + token()))
                .andExpect(status().isUnauthorized());
        verify(auth, never()).requireActiveAccount(anyString());
    }

    @Test
    void registrationValidatesFieldsAndDoesNotReturnRejectedPassword() throws Exception {
        mvc.perform(post("/api/dmc/auth/register").contentType("application/json")
                .content("{\"email\":\"invalid\",\"displayName\":\"Person\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
        verify(auth, never()).register(any());
    }

    @Test
    void browserPreflightAllowsConfiguredOriginButRejectsOtherOrigins() throws Exception {
        mvc.perform(options("/api/dmc/auth/login").header("Origin", "http://localhost:8081")
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8081"));
        mvc.perform(options("/api/dmc/auth/login").header("Origin", "https://other.example")
                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void forgedTokenRolesCannotElevateCitizenButStoredOfficerRoleWorks() throws Exception {
        when(users.findById("user-1")).thenReturn(Optional.of(account(true)));
        mvc.perform(get("/api/dmc/test/officer").header("Authorization", "Bearer " + token()))
                .andExpect(status().isForbidden());
        when(users.findById("user-1")).thenReturn(Optional.of(new UserAccount("user-1", "a@example.com", "Officer",
                "hash", Set.of(AccountRole.DUTY_OFFICER), true, Instant.now())));
        mvc.perform(get("/api/dmc/test/officer").header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk());
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class TestRoutes {
        @org.springframework.context.annotation.Bean
        OfficerRoute officerRoute() { return new OfficerRoute(); }
    }

    @org.springframework.web.bind.annotation.RestController
    static class OfficerRoute {
        @org.springframework.web.bind.annotation.GetMapping("/api/dmc/test/officer")
        @org.springframework.security.access.prepost.PreAuthorize("hasRole('DUTY_OFFICER')")
        public String officer() { return "ok"; }
    }

    private UserAccount account(boolean enabled) {
        return new UserAccount("user-1", "a@example.com", "Citizen", "never-return-this-hash",
                Set.of(AccountRole.CITIZEN), enabled, Instant.now());
    }

    private String token() {
        var claims = JwtClaimsSet.builder().subject("user-1").issuer("dmc-backend")
                .audience(List.of("dmc-api")).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900))
                .claim("roles", List.of("DUTY_OFFICER")).build(); // Forged role metadata is ignored.
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
