package com.booksmith.identity.internal.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** Não é um @Component de propósito: é registrado só dentro da cadeia do Spring Security. */
class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    private static final String AUTH_PREFIX = "/api/v1/auth/";
    private static final String MFA_PATH = "/api/v1/me/mfa";

    private final JwtTokenProvider tokenProvider;

    JwtAuthenticationFilter(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            var verified = tokenProvider.verify(header.substring(BEARER.length()));
            if (verified.isPresent() && verified.get().profilePending() && !allowedWhilePending(request)) {
                // Conta nova do Google: até informar a data de nascimento (idade mínima), só completa o cadastro
                forbid(response, PROFILE_INCOMPLETE);
                return;
            }
            if (verified.isPresent() && verified.get().mfaPending() && !allowedWhileMfaPending(request)) {
                // MFA obrigatório: até ativar um método, só configura o MFA
                forbid(response, MFA_SETUP_REQUIRED);
                return;
            }
            verified.ifPresent(v -> SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(v.user(), null, List.of())));
        }
        filterChain.doFilter(request, response);
    }

    // Corpos fixos (texto literal, nada da requisição entra neles)
    private static final String PROFILE_INCOMPLETE = """
            {"status":403,"title":"Forbidden","code":"profile_incomplete","detail":"Complete seu cadastro para continuar."}""";
    private static final String MFA_SETUP_REQUIRED = """
            {"status":403,"title":"Forbidden","code":"mfa_setup_required",\
            "detail":"Ative a verificação em duas etapas para continuar."}""";

    private static void forbid(HttpServletResponse response, String body) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(body);
    }

    /** Com o cadastro pendente: completar o cadastro e as rotas de sessão (renovar, sair). */
    static boolean allowedWhilePending(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith(AUTH_PREFIX) || path.equals("/api/v1/profile/complete");
    }

    /** Sem MFA (obrigatório): configurar o MFA, ler o próprio perfil (o e-mail da tela) e as rotas de sessão. */
    static boolean allowedWhileMfaPending(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith(AUTH_PREFIX)
                || path.equals(MFA_PATH) || path.startsWith(MFA_PATH + "/")
                || "GET".equals(request.getMethod()) && path.equals("/api/v1/profile");
    }
}
