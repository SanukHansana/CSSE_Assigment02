package com.dmc.backend.auth;

import java.time.Instant;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Password hashes are internal persistence data; controllers return explicit safe projections. */
@Document(collection = "users")
public record UserAccount(@Id String id, String email, String displayName, String passwordHash,
                          Set<AccountRole> roles, boolean enabled, Instant createdAt,
                          AccountRole requestedRole, String approvalStatus, String reviewedBy, Instant reviewedAt, String approvalNote) {
    // Existing account documents and callers remain compatible.
    public UserAccount(String id, String email, String displayName, String passwordHash,
                       Set<AccountRole> roles, boolean enabled, Instant createdAt) {
        this(id,email,displayName,passwordHash,roles,enabled,createdAt,null,null,null,null,null);
    }
    public Set<AccountRole> effectiveRoles() {
        return approvalStatus==null || approvalStatus.equals("APPROVED") ? roles : Set.of();
    }
    public String effectiveApprovalStatus() { return approvalStatus==null ? "APPROVED" : approvalStatus; }
    public UserAccount {
        roles = Set.copyOf(roles);
    }
}
