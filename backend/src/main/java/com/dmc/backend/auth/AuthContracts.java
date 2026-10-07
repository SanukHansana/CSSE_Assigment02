package com.dmc.backend.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;

/** Separate transport records prevent accidental disclosure of hashes or persistence fields. */
public final class AuthContracts {
    private AuthContracts() { }

    public record RegisterRequest(@NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 100) String displayName,
            @NotBlank @Size(max = 72) String password, AccountRole requestedRole) {
        public RegisterRequest(String email,String displayName,String password) { this(email,displayName,password,null); }
    }

    public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 72) String password) { }

    public record UserResponse(String id, String email, String displayName, Set<AccountRole> roles, AccountRole requestedRole, String approvalStatus, String approvalNote) {
        static UserResponse from(UserAccount account) {
            return new UserResponse(account.id(), account.email(), account.displayName(), account.effectiveRoles(), account.requestedRole(), account.effectiveApprovalStatus(), account.approvalNote());
        }
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn,
                                Instant expiresAt, UserResponse user) { }
}
