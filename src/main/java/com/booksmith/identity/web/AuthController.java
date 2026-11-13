package com.booksmith.identity.web;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.internal.auth.AuthService;
import com.booksmith.identity.web.AuthDtos.ForgotPasswordRequest;
import com.booksmith.identity.web.AuthDtos.GoogleExchangeRequest;
import com.booksmith.identity.web.AuthDtos.LoginRequest;
import com.booksmith.identity.web.AuthDtos.RegisterRequest;
import com.booksmith.identity.web.AuthDtos.ResetPasswordRequest;
import com.booksmith.identity.web.AuthDtos.SessionResponse;
import com.booksmith.identity.web.AuthDtos.VerifyEmailRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Login com Google: o frontend redireciona para {@code /oauth2/authorization/google}. */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
class AuthController {

    private static final String BEARER = "Bearer ";

    private final AuthService authService;
    private final com.booksmith.identity.internal.invite.InviteService inviteService;

    @PostMapping("/login")
    SessionResponse login(@RequestBody @Valid LoginRequest request,
                          @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return SessionResponse.from(authService.login(request.email(), request.password(), userAgent));
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    void register(@RequestBody @Valid RegisterRequest request) {
        authService.register(request.toRegistration());
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void verifyEmail(@RequestBody @Valid VerifyEmailRequest request) {
        authService.verifyEmail(request.email(), request.code());
    }

    /** O que a tela de cadastro precisa saber: se o beta está fechado (convite obrigatório). */
    @org.springframework.web.bind.annotation.GetMapping("/config")
    AuthConfig config() {
        return new AuthConfig(inviteService.required());
    }

    /** Confere um código de convite sem gastar (antes de mandar para o Google). */
    @PostMapping("/invite/check")
    InviteCheck checkInvite(@RequestBody @Valid InviteCheckRequest request) {
        return new InviteCheck(inviteService.valid(request.code()));
    }

    record AuthConfig(boolean inviteRequired) {
    }

    record InviteCheckRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 40) String code) {
    }

    record InviteCheck(boolean valid) {
    }

    /** Novo código para confirmar o e-mail; responde 204 sempre (não revela se a conta existe). */
    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resendVerification(@RequestBody @Valid ForgotPasswordRequest request) {
        authService.resendVerification(request.email());
    }

    /** Troca o código de uso único do login com Google pela sessão. */
    @PostMapping("/google/exchange")
    SessionResponse exchangeGoogleCode(@RequestBody @Valid GoogleExchangeRequest request,
                                       @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return SessionResponse.from(authService.exchangeGoogleCode(request.code(), userAgent));
    }

    /** Segunda etapa do login: o código do desafio ({@code mfa_required}) abre a sessão. */
    @PostMapping("/mfa/verify")
    SessionResponse verifyMfa(@RequestBody @Valid AuthDtos.MfaVerifyRequest request,
                              @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return SessionResponse.from(authService.completeMfa(request.challenge(), request.code(), userAgent));
    }

    /** Segunda etapa por e-mail: manda o código (um por minuto). */
    @PostMapping("/mfa/email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void sendMfaEmail(@RequestBody @Valid AuthDtos.MfaChallengeRequest request) {
        authService.sendMfaEmail(request.challenge());
    }

    @PostMapping("/refresh")
    SessionResponse refresh(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return SessionResponse.from(authService.refresh(bearerToken(authorization)));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        authService.logout(bearerToken(authorization));
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void forgotPassword(@RequestBody @Valid ForgotPasswordRequest request) {
        authService.forgotPassword(request.email());
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetPassword(@RequestBody @Valid ResetPasswordRequest request) {
        authService.resetPassword(request.token(), request.password());
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER)) {
            throw ApiException.unauthorized("Envie o header Authorization: Bearer <token>.");
        }
        return authorization.substring(BEARER.length());
    }
}
