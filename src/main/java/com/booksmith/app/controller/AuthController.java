package com.booksmith.app.controller;

import com.booksmith.app.dto.request.LoginRequest;
import com.booksmith.app.dto.request.RegisterRequest;
import com.booksmith.app.service.LoginService;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RestControllerAdvice
public class AuthController {
    private LoginService loginService;
    public AuthController(LoginService loginService) {
        this.loginService = loginService;
    }

    @PostMapping("/login")
    public ResponseEntity login(@RequestBody LoginRequest loginRequest) throws Exception {
        return ResponseEntity.ok().body(loginService.login(loginRequest));
    }

    @PostMapping("/register")
    public ResponseEntity register(@RequestBody @Validated RegisterRequest registerRequest){
        loginService.register(registerRequest);
        return ResponseEntity.status(201).build();
    }

    @PostMapping("/refresh-token")
    public ResponseEntity refreshToken(@RequestHeader("Authorization") String authorizationHeader) {
        return ResponseEntity.ok().body(loginService.refreshToken(authorizationHeader));
    }

    @PostMapping("/logout")
    public ResponseEntity logout(@RequestHeader("Authorization") String authorizationHeader) {
        loginService.logout(authorizationHeader);
        return ResponseEntity.noContent().build();
    }
}
