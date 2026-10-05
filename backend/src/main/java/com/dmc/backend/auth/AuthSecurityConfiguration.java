package com.dmc.backend.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(AuthSettings.class)
public class AuthSecurityConfiguration {
    @Bean
    Clock serverClock() { return Clock.systemUTC(); }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    SecretKey jwtSigningKey(AuthSettings settings) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(settings.secret());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("JWT_SECRET must be a Base64-encoded key of at least 32 random bytes");
        }
        if (bytes.length < 32) {
            throw new IllegalArgumentException("JWT_SECRET must contain at least 32 random bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key, AuthSettings settings) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> requiredClaims = jwt -> {
            if (jwt.getExpiresAt() == null || jwt.getIssuedAt() == null || jwt.getSubject() == null
                    || jwt.getSubject().isBlank() || jwt.getAudience() == null
                    || !jwt.getAudience().contains(settings.audience())) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid token claims", null));
            }
            return OAuth2TokenValidatorResult.success();
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(settings.issuer()), requiredClaims));
        return decoder;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, UserAccountRepository users,
                                             @org.springframework.beans.factory.annotation.Qualifier("corsConfigurationSource")
                                             CorsConfigurationSource cors) throws Exception {
        http.cors(config -> config.configurationSource(cors))
                // This API uses Authorization bearer tokens only; it does not authenticate via cookies.
                .csrf(config -> config.disable())
                .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(config -> config
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/dmc").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/api/dmc/auth/register", "/api/dmc/auth/login").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(config -> config
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(response, 401, "AUTHENTICATION_REQUIRED"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(response, 403, "ACCESS_DENIED")))
                .oauth2ResourceServer(config -> config
                        .authenticationEntryPoint((request, response, exception) -> {
                            boolean unavailable = exception instanceof OAuth2AuthenticationException oauth
                                    && "server_error".equals(oauth.getError().getErrorCode());
                            writeError(response, unavailable ? 503 : 401,
                                    unavailable ? "AUTH_UNAVAILABLE" : "INVALID_TOKEN");
                        })
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(token -> {
                            // Ignore any token/request role claims. Load current enabled roles from MongoDB.
                            UserAccount account;
                            try {
                                account = users.findById(token.getSubject()).filter(UserAccount::enabled)
                                        .orElseThrow(() -> new OAuth2AuthenticationException("invalid_token"));
                            } catch (DataAccessException exception) {
                                throw new OAuth2AuthenticationException(new OAuth2Error("server_error"));
                            }
                            var authorities = account.roles().stream()
                                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList();
                            return new JwtAuthenticationToken(token, authorities, account.id());
                        })));
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AuthSettings settings) {
        CorsConfiguration cors = new CorsConfiguration();
        var origins = Arrays.stream(settings.allowedOrigins().split(","))
                .map(String::strip).filter(origin -> !origin.isEmpty()).toList();
        for (String origin : origins) {
            java.net.URI uri = java.net.URI.create(origin);
            if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()))
                    || uri.getHost() == null || origin.contains("*") || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getUserInfo() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty())) {
                throw new IllegalArgumentException("WEB_ALLOWED_ORIGINS must contain exact HTTP(S) origins without paths");
            }
        }
        cors.setAllowedOrigins(origins);
        cors.setAllowedMethods(java.util.List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type", "If-Match", "Idempotency-Key"));
        cors.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    private static void writeError(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"Authentication request could not be authorized.\"}");
    }
}
