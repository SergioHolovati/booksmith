package com.booksmith.identity.web;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.internal.auth.AuthService;
import com.booksmith.identity.internal.security.SessionCookie;
import com.booksmith.identity.web.AuthDtos.ForgotPasswordRequest;
import com.booksmith.identity.web.AuthDtos.GoogleExchangeRequest;
import com.booksmith.identity.web.AuthDtos.LoginRequest;
import com.booksmith.identity.web.AuthDtos.RegisterRequest;
import com.booksmith.identity.web.AuthDtos.ResetPasswordRequest;
import com.booksmith.identity.web.AuthDtos.SessionResponse;
import com.booksmith.identity.web.AuthDtos.VerifyEmailRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login com Google: o frontend redireciona para {@code /oauth2/authorization/google}. Toda rota que abre ou renova a
 * sessão devolve o token de acesso no corpo (o app guarda só na memória) e o refresh token no cookie
 * {@code HttpOnly} ({@link SessionCookie}).
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
class AuthController {

    private static final String BEARER = "Bearer ";

    private final AuthService authService;
    private final com.booksmith.identity.internal.invite.InviteService inviteService;
    private final SessionCookie sessionCookie;
    private final com.booksmith.identity.internal.terms.TermsService termsService;

    @PostMapping("/login")
    SessionResponse login(@RequestBody @Valid LoginRequest request,
                          @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
                          HttpServletResponse response) {
        return started(authService.login(request.email(), request.password(), userAgent), response);
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

    /** O que as telas públicas precisam saber: se o beta está fechado (convite obrigatório) e a versão dos termos. */
    @org.springframework.web.bind.annotation.GetMapping("/config")
    AuthConfig config() {
        return new AuthConfig(inviteService.required(), termsService.version());
    }

    /** Confere um código de convite sem gastar (antes de mandar para o Google). */
    @PostMapping("/invite/check")
    InviteCheck checkInvite(@RequestBody @Valid InviteCheckRequest request) {
        return new InviteCheck(inviteService.valid(request.code()));
    }

    record AuthConfig(boolean inviteRequired, String termsVersion) {
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
                                       @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
                                       HttpServletResponse response) {
        return started(authService.exchangeGoogleCode(request.code(), userAgent), response);
    }

    /** Segunda etapa do login: o código do desafio ({@code mfa_required}) abre a sessão. */
    @PostMapping("/mfa/verify")
    SessionResponse verifyMfa(@RequestBody @Valid AuthDtos.MfaVerifyRequest request,
                              @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
                              HttpServletResponse response) {
        return started(authService.completeMfa(request.challenge(), request.code(), userAgent), response);
    }

    /** Segunda etapa por e-mail: manda o código (um por minuto). */
    @PostMapping("/mfa/email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void sendMfaEmail(@RequestBody @Valid AuthDtos.MfaChallengeRequest request) {
        authService.sendMfaEmail(request.challenge());
    }

    /** Renova a sessão pelo refresh token do cookie (o token de acesso não serve para renovar). */
    @PostMapping("/refresh")
    SessionResponse refresh(@CookieValue(name = SessionCookie.NAME, required = false) String refreshToken,
                            HttpServletResponse response) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw ApiException.unauthorized("Sessão expirada. Entre novamente.");
        }
        return started(authService.refresh(refreshToken), response);
    }

    /** Sai pelo cookie e/ou pelo header Authorization; sempre apaga o cookie. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@CookieValue(name = SessionCookie.NAME, required = false) String refreshToken,
                @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                HttpServletResponse response) {
        String accessToken = authorization != null && authorization.startsWith(BEARER)
                ? authorization.substring(BEARER.length()) : null;
        authService.logout(refreshToken, accessToken);
        sessionCookie.clear(response);
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

    private SessionResponse started(AuthService.Session session, HttpServletResponse response) {
        sessionCookie.write(response, session.token().refreshToken());
        return SessionResponse.from(session);
    }
}
