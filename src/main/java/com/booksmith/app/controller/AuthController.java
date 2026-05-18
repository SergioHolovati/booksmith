package com.booksmith.app.controller;

import com.booksmith.app.dto.request.ForgotPasswordRequestDTO;
import com.booksmith.app.dto.request.EmailVerificationRequestDTO;
import com.booksmith.app.dto.request.LoginRequestDTO;
import com.booksmith.app.dto.request.RegisterRequestDTO;
import com.booksmith.app.service.AuthService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RestControllerAdvice
public class AuthController {
    private AuthService authService;
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity login(@RequestBody LoginRequestDTO loginRequestDTO){
        return ResponseEntity.ok().body(authService.login(loginRequestDTO));
    }

    @GetMapping("/google")
    public ResponseEntity<Void> googleLogin() {
        return ResponseEntity.status(302)
                .header(HttpHeaders.LOCATION, "/oauth2/authorization/google")
                .build();
    }

    @PostMapping("/register")
    public ResponseEntity register(@RequestBody @Validated RegisterRequestDTO registerRequestDTO){
        authService.register(registerRequestDTO);
        return ResponseEntity.status(201).build();
    }

    @PostMapping("/verify-email")
    public ResponseEntity verifyEmail(@RequestBody @Validated EmailVerificationRequestDTO request) {
        authService.verifyEmail(request.getEmail(), request.getToken());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refresh-token")
    public ResponseEntity refreshToken(@RequestHeader("Authorization") String authorizationHeader) {
        return ResponseEntity.ok().body(authService.refreshToken(authorizationHeader));
    }

    @PostMapping("/logout")
    public ResponseEntity logout(@RequestHeader("Authorization") String authorizationHeader) {
        authService.logout(authorizationHeader);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(
            @RequestBody ForgotPasswordRequestDTO request
    ) {
        authService.forgotPassword(request.getEmail());
        return ResponseEntity.noContent().build();
    }
}
