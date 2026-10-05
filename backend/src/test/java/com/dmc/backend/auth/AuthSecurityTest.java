package com.dmc.backend.auth;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

class AuthSecurityTest {
    final AuthSecurityConfiguration config = new AuthSecurityConfiguration();
    final AuthSettings settings = AuthServiceTest.SETTINGS;
    final JwtEncoder encoder = config.jwtEncoder(config.jwtSigningKey(settings));
    final JwtDecoder decoder = config.jwtDecoder(config.jwtSigningKey(settings), settings);

    @Test
    void rejectsMissingInvalidOrShortSecrets() {
        for (String key : new String[] {"", "not-base64", "c2hvcnQ="}) {
            assertThatThrownBy(() -> config.jwtSigningKey(new AuthSettings(key, "issuer", "audience", 900, "")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsExpiredTokensWrongIssuerWrongAudienceAndAbsentRequiredClaims() {
        Instant now = Instant.now();
        for (JwtClaimsSet claims : List.of(
                claims("dmc-backend", "dmc-api", now.minusSeconds(120)),
                claims("other-issuer", "dmc-api", now.plusSeconds(900)),
                claims("dmc-backend", "other-api", now.plusSeconds(900)),
                JwtClaimsSet.builder().issuer("dmc-backend").subject("user-1")
                        .issuedAt(now).expiresAt(now.plusSeconds(900)).build(),
                JwtClaimsSet.builder().issuer("dmc-backend").audience(List.of("dmc-api"))
                        .subject("user-1").issuedAt(now).build())) {
            assertThatThrownBy(() -> decoder.decode(sign(claims))).isInstanceOf(JwtException.class);
        }
    }

    @Test
    void rejectsTokenSignedByAnotherKey() {
        var other = new AuthSettings("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=", "dmc-backend", "dmc-api", 900, "");
        var token = config.jwtEncoder(config.jwtSigningKey(other)).encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims("dmc-backend", "dmc-api", Instant.now().plusSeconds(900))))
                .getTokenValue();
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void corsUsesExactOriginsAndNoCookieCredentials() {
        assertThat(config.corsConfigurationSource(settings)).isNotNull();
        assertThatThrownBy(() -> config.corsConfigurationSource(
                new AuthSettings(settings.secret(), "issuer", "audience", 900, "https://*.example.com")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void importedEnvQuotesAreHandledWithoutModifyingOtherPropertySources() {
        var environment = new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "Config resource 'file [.env]'", java.util.Map.of("LOCAL_TEST_KEY", "\"local-value\"")));
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "other-source", java.util.Map.of("OTHER_TEST_KEY", "\"keep-quotes\"")));
        new QuotedEnvValuesPostProcessor().postProcessEnvironment(environment, new org.springframework.boot.SpringApplication());
        assertThat(environment.getProperty("LOCAL_TEST_KEY")).isEqualTo("local-value");
        assertThat(environment.getProperty("OTHER_TEST_KEY")).isEqualTo("\"keep-quotes\"");
    }

    private JwtClaimsSet claims(String issuer, String audience, Instant expiration) {
        return JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience)).subject("user-1")
                .issuedAt(Instant.now().minusSeconds(300)).expiresAt(expiration).build();
    }

    private String sign(JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
