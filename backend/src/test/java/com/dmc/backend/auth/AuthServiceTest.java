package com.dmc.backend.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class AuthServiceTest {
    static final AuthSettings SETTINGS = new AuthSettings(
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "dmc-backend", "dmc-api", 900, "http://localhost:8081");
    final UserAccountRepository users = mock(UserAccountRepository.class);
    final MongoTemplate mongo = mock(MongoTemplate.class);
    final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(4);
    final Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
    AuthService auth;
    JwtDecoder decoder;

    @BeforeEach
    void setup() {
        var config = new AuthSecurityConfiguration();
        var key = config.jwtSigningKey(SETTINGS);
        decoder = config.jwtDecoder(key, SETTINGS);
        auth = new AuthService(users, mongo, passwords, config.jwtEncoder(key), SETTINGS, clock);
        when(mongo.indexOps(UserAccount.class)).thenReturn(mock(IndexOperations.class));
        when(users.insert(any(UserAccount.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void registrationNormalizesEmailHashesPasswordAndAssignsCitizenOnly() {
        String password = "a-good-password-123";
        var result = auth.register(new AuthContracts.RegisterRequest("Person@Example.com", " Citizen ", password));
        var capture = org.mockito.ArgumentCaptor.forClass(UserAccount.class);
        verify(users).insert(capture.capture());
        var stored = capture.getValue();
        assertThat(stored.email()).isEqualTo("person@example.com");
        assertThat(stored.displayName()).isEqualTo("Citizen");
        assertThat(stored.passwordHash()).isNotEqualTo(password);
        assertThat(passwords.matches(password, stored.passwordHash())).isTrue();
        assertThat(result.roles()).containsExactly(AccountRole.CITIZEN);
        assertThat(stored.createdAt()).isEqualTo(clock.instant());
        verify(mongo.indexOps(UserAccount.class)).createIndex(any());
    }

    @Test
    void duplicateRegistrationReturnsConflict() {
        when(users.insert(any(UserAccount.class))).thenThrow(new DuplicateKeyException("private database detail"));
        assertThatThrownBy(() -> auth.register(new AuthContracts.RegisterRequest("a@example.com", "Citizen", "password-123456")))
                .isInstanceOf(AuthException.class).hasMessage("An account with that email already exists.");
    }

    @Test
    void loginIssuesVerifiableShortLivedTokenWithoutRoleOrPasswordClaims() {
        var account = account(true);
        when(users.findByEmail(account.email())).thenReturn(Optional.of(account));
        var response = auth.login(new AuthContracts.LoginRequest("PERSON@EXAMPLE.COM", "password-123456"));
        var token = decoder.decode(response.accessToken());
        assertThat(token.getSubject()).isEqualTo(account.id());
        assertThat(token.getAudience()).containsExactly("dmc-api");
        assertThat(token.getClaims()).doesNotContainKeys("roles", "password", "passwordHash");
        assertThat(response.expiresIn()).isEqualTo(900);
        assertThat(response.expiresAt()).isEqualTo(clock.instant().plusSeconds(900));
    }

    @Test
    void unknownWrongPasswordAndDisabledAccountsUseSameCredentialError() {
        when(users.findByEmail("person@example.com")).thenReturn(Optional.empty());
        assertInvalid("password-123456");
        when(users.findByEmail("person@example.com")).thenReturn(Optional.of(account(true)));
        assertInvalid("wrong-password");
        when(users.findByEmail("person@example.com")).thenReturn(Optional.of(account(false)));
        assertInvalid("password-123456");
    }

    @Test
    void bcryptByteLimitRejectsMultibytePasswordsBeforeDatabaseWrite() {
        assertThatThrownBy(() -> auth.register(new AuthContracts.RegisterRequest("a@example.com", "Citizen", "\u00e9".repeat(40))))
                .isInstanceOf(AuthException.class).hasMessageContaining("72 UTF-8 bytes");
        verify(users, never()).insert(any(UserAccount.class));
    }

    private UserAccount account(boolean enabled) {
        return new UserAccount("user-1", "person@example.com", "Citizen", passwords.encode("password-123456"),
                Set.of(AccountRole.CITIZEN), enabled, clock.instant());
    }

    private void assertInvalid(String password) {
        assertThatThrownBy(() -> auth.login(new AuthContracts.LoginRequest("person@example.com", password)))
                .isInstanceOf(AuthException.class).hasMessage("Invalid email or password.");
    }
}
