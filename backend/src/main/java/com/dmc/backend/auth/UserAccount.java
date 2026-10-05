package com.dmc.backend.auth;

import java.time.Instant;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Password hashes are internal persistence data; controllers return explicit safe projections. */
@Document(collection = "users")
public record UserAccount(@Id String id, String email, String displayName, String passwordHash,
                          Set<AccountRole> roles, boolean enabled, Instant createdAt) {
    public UserAccount {
        roles = Set.copyOf(roles);
    }
}
