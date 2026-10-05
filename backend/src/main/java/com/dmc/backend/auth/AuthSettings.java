package com.dmc.backend.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** No usable default signing secret; a missing or weak key prevents startup. */
@ConfigurationProperties(prefix = "dmc.auth")
public record AuthSettings(String secret, String issuer, String audience, long ttlSeconds, String allowedOrigins) {
    public AuthSettings {
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("JWT issuer and audience are required");
        }
        if (ttlSeconds < 60 || ttlSeconds > 3600) {
            throw new IllegalArgumentException("JWT TTL must be between 60 and 3600 seconds");
        }
    }
}
