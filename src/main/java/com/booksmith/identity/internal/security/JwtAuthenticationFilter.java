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
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/problem+json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write("""
                        {"status":403,"title":"Forbidden","code":"profile_incomplete",                        "detail":"Complete seu cadastro para continuar."}""");
                return;
            }
            verified.ifPresent(v -> SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(v.user(), null, List.of())));
        }
        filterChain.doFilter(request, response);
    }

    /** Com o cadastro pendente: completar o cadastro e as rotas de sessão (renovar, sair). */
    static boolean allowedWhilePending(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/v1/auth/") || path.equals("/api/v1/profile/complete");
    }
}
