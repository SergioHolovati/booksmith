package com.booksmith.identity.web;

import com.booksmith.identity.api.AuthenticatedStaff;
import com.booksmith.identity.api.CurrentStaff;
import com.booksmith.identity.internal.staff.StaffAuthService;
import com.booksmith.identity.internal.staff.StaffProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * Login da equipe (backoffice), separado do dos clientes: {@code POST login} (e-mail e senha) devolve o desafio,
 * {@code POST verify} (código do autenticador) abre a sessão. O refresh token vai no cookie
 * {@value #COOKIE} (HttpOnly, só para {@value #COOKIE_PATH}). Convite: {@code POST invite} mostra o QR code e
 * {@code POST invite/accept} cria a senha e ativa o autenticador.
 */
@RestController
@RequestMapping("/api/v1/staff")
@RequiredArgsConstructor
class StaffAuthController {

    static final String COOKIE = "booksmith_staff_refresh";
    static final String COOKIE_PATH = "/api/v1/staff/auth";
    private static final Duration COOKIE_TTL = Duration.ofHours(12);

    private final StaffAuthService auth;
    private final CurrentStaff currentStaff;
    private final StaffProperties properties;

    record LoginRequest(String email, String password) {
    }

    record ChallengeResponse(String challenge) {
    }

    record VerifyRequest(String challenge, String code) {
    }

    record SessionResponse(String accessToken) {
    }

    record InviteRequest(String token) {
    }

    record AcceptRequest(String token, String password, String code) {
    }

    record AcceptResponse(List<String> recoveryCodes) {
    }

    record MeResponse(String id, String email, String name, String role) {
    }

    @PostMapping("/auth/login")
    ChallengeResponse login(@RequestBody LoginRequest request) {
        return new ChallengeResponse(auth.login(request.email(), request.password()));
    }

    @PostMapping("/auth/verify")
    SessionResponse verify(@RequestBody VerifyRequest request, HttpServletResponse response) {
        StaffAuthService.Session session = auth.verify(request.challenge(), request.code());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(session.refreshToken(), COOKIE_TTL));
        return new SessionResponse(session.accessToken());
    }

    @PostMapping("/auth/refresh")
    SessionResponse refresh(@CookieValue(name = COOKIE, required = false) String refreshToken,
                            HttpServletResponse response) {
        StaffAuthService.Session session = auth.refresh(refreshToken);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(session.refreshToken(), COOKIE_TTL));
        return new SessionResponse(session.accessToken());
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@CookieValue(name = COOKIE, required = false) String refreshToken, HttpServletRequest request,
                HttpServletResponse response) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        auth.logout(refreshToken, header != null && header.startsWith("Bearer ") ? header.substring(7) : null);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO));
    }

    /** POST (e não GET com o token no caminho): o token do convite não vai parar em log de acesso. */
    @PostMapping("/auth/invite")
    StaffAuthService.InviteView invite(@RequestBody InviteRequest request) {
        return auth.invite(request.token());
    }

    @PostMapping("/auth/invite/accept")
    AcceptResponse accept(@RequestBody AcceptRequest request) {
        return new AcceptResponse(auth.accept(request.token(), request.password(), request.code()));
    }

    /** Quem está logado no backoffice (e o papel, para o menu). */
    @GetMapping("/me")
    MeResponse me() {
        AuthenticatedStaff staff = currentStaff.get();
        return new MeResponse(staff.id().toString(), staff.email(), staff.name(), staff.role().name());
    }

    private String cookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE, value)
                .httpOnly(true)
                .secure(properties.adminUrl() != null && properties.adminUrl().startsWith("https://"))
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build()
                .toString();
    }
}
