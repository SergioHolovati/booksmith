package com.booksmith.identity.internal.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * O cookie do refresh token: {@code HttpOnly} (o JavaScript não lê), {@code SameSite=Strict} (outro site não manda)
 * e só para as rotas de sessão ({@code /api/v1/auth}). {@code Secure} quando o app roda em HTTPS (o endereço do app
 * diz isso); em desenvolvimento, pelo IP da rede local em HTTP, o navegador não guardaria um cookie {@code Secure}.
 */
@Component
public class SessionCookie {

    public static final String NAME = "booksmith_refresh";
    static final String PATH = "/api/v1/auth";

    private final SecurityProperties properties;

    SessionCookie(SecurityProperties properties) {
        this.properties = properties;
    }

    /** Grava o refresh token; sem refresh token novo (ex.: token reemitido na mesma sessão), não mexe no cookie. */
    public void write(HttpServletResponse response, String refreshToken) {
        if (refreshToken != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, cookie(refreshToken, properties.sessionTtl()));
        }
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO));
    }

    private String cookie(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(properties.frontendUrl() != null && properties.frontendUrl().startsWith("https://"))
                .sameSite("Strict")
                .path(PATH)
                .maxAge(maxAge)
                .build()
                .toString();
    }
}
