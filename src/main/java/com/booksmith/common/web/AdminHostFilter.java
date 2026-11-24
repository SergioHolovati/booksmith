package com.booksmith.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

/**
 * As rotas da equipe ({@code /api/v1/admin/**} e o login dela, {@code /api/v1/staff/**}) só existem pelo endereço
 * do backoffice ({@code booksmith.admin.host}, ex.: adminbooksmith.com.br). Pelo endereço do app, 404 como se não existissem: o Caddy já barra, isto é a segunda
 * porta (vale mesmo se alguém mexer no Caddy). Sem o endereço configurado (desenvolvimento), não faz nada.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class AdminHostFilter extends OncePerRequestFilter {

    static final String ADMIN_PATH = "/api/v1/admin/";
    /** O login e a sessão da equipe também só existem pelo backoffice. */
    static final String STAFF_PATH = "/api/v1/staff/";

    private final String adminHost;

    public AdminHostFilter(@Value("${booksmith.admin.host:}") String adminHost) {
        this.adminHost = adminHost == null ? "" : adminHost.strip().toLowerCase(Locale.ROOT);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return adminHost.isEmpty() || !path.startsWith(ADMIN_PATH) && !path.startsWith(STAFF_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String host = request.getServerName() == null ? "" : request.getServerName().toLowerCase(Locale.ROOT);
        if (adminHost.equals(host)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"status\":404,\"title\":\"Not Found\",\"code\":\"not_found\"}");
    }
}
