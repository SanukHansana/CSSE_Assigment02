package com.dmc.backend.auth;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dmc/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthContracts.UserResponse register(@Valid @RequestBody AuthContracts.RegisterRequest request) {
        return auth.register(request);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthContracts.TokenResponse> login(@Valid @RequestBody AuthContracts.LoginRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Pragma", "no-cache")
                .body(auth.login(request));
    }

    @GetMapping("/me")
    public AuthContracts.UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return AuthContracts.UserResponse.from(auth.requireActiveAccount(jwt.getSubject()));
    }
}
