package com.dmc.backend.auth;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

/** Custom credential authentication with Spring's password encoder and JWT primitives. */
@Service
public class AuthService {
    private final UserAccountRepository users;
    private final MongoTemplate mongo;
    private final PasswordEncoder passwords;
    private final JwtEncoder tokens;
    private final AuthSettings settings;
    private final Clock clock;
    private final String dummyHash;

    public AuthService(UserAccountRepository users, MongoTemplate mongo, PasswordEncoder passwords,
                       JwtEncoder tokens, AuthSettings settings, Clock clock) {
        this.users = users;
        this.mongo = mongo;
        this.passwords = passwords;
        this.tokens = tokens;
        this.settings = settings;
        this.clock = clock;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public AuthContracts.UserResponse register(AuthContracts.RegisterRequest request) {
        checkPasswordBytes(request.password());
        if (request.displayName().isBlank()) {
            throw new AuthException(BAD_REQUEST, "INVALID_DISPLAY_NAME", "Provide a display name.");
        }
        // Enforce uniqueness before inserting, even if the manual migration has not been applied.
        // No DB connection is attempted during application startup.
        mongo.indexOps(UserAccount.class).createIndex(new Index("email", Sort.Direction.ASC)
                .unique().named("uq_user_email"));
        UserAccount account = new UserAccount(UUID.randomUUID().toString(), normalizeEmail(request.email()),
                request.displayName().strip(), passwords.encode(request.password()),
                Set.of(AccountRole.CITIZEN), true, clock.instant());
        try {
            return AuthContracts.UserResponse.from(users.insert(account));
        } catch (DuplicateKeyException exception) {
            throw new AuthException(org.springframework.http.HttpStatus.CONFLICT,
                    "ACCOUNT_ALREADY_EXISTS", "An account with that email already exists.");
        }
    }

    public AuthContracts.TokenResponse login(AuthContracts.LoginRequest request) {
        checkPasswordBytes(request.password());
        UserAccount account = users.findByEmail(normalizeEmail(request.email())).orElse(null);
        boolean matches = passwords.matches(request.password(), account == null ? dummyHash : account.passwordHash());
        if (account == null || !matches || !account.enabled()) {
            throw new AuthException(UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password.");
        }
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plusSeconds(settings.ttlSeconds());
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(settings.issuer()).audience(java.util.List.of(settings.audience()))
                .subject(account.id()).issuedAt(issuedAt).expiresAt(expiresAt).id(UUID.randomUUID().toString()).build();
        String token = tokens.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new AuthContracts.TokenResponse(token, "Bearer", settings.ttlSeconds(), expiresAt,
                AuthContracts.UserResponse.from(account));
    }

    public UserAccount requireActiveAccount(String subject) {
        return users.findById(subject).filter(UserAccount::enabled)
                .orElseThrow(() -> new AuthException(UNAUTHORIZED, "INVALID_ACCOUNT", "Authentication required."));
    }

    private static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static void checkPasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new AuthException(BAD_REQUEST, "PASSWORD_TOO_LONG", "Password must not exceed 72 UTF-8 bytes.");
        }
    }
}
