package com.booksmith.monitoring.internal;

import com.booksmith.identity.api.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.UUID;

/**
 * Conta cada requisição da API. Fica antes da segurança (logo depois do rastreio), para ver também o que ela barra
 * (401, 429). A rota vem do padrão que o Spring escolheu ({@code /api/v1/books/{id}}), nunca o caminho com IDs; a
 * conta vem do {@link #USER} que o {@link UserMarker} deixa na requisição (a segurança limpa o contexto na volta).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class TrafficFilter extends OncePerRequestFilter {

    static final String USER = TrafficFilter.class.getName() + ".user";

    private final TrafficCounter counter;
    private final Clock clock;
    private final ObjectProvider<HandlerMapping> routes;

    TrafficFilter(TrafficCounter counter, Clock clock,
                  @Qualifier("requestMappingHandlerMapping") ObjectProvider<HandlerMapping> routes) {
        this.counter = counter;
        this.clock = clock;
        this.routes = routes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/") || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        boolean finished = false;
        try {
            chain.doFilter(request, response);
            finished = true;
        } finally {
            long millis = (System.nanoTime() - start) / 1_000_000;
            int status = finished ? response.getStatus() : 500;
            String route = route(request);
            if (route == null && (status == 401 || status == 403)) {
                route = lookup(request);
            }
            counter.record(clock.instant(), request.getMethod(), route, request.getRequestURI(), status, millis,
                    request.getRemoteAddr(),
                    request.getAttribute(USER) instanceof UUID user ? user : null);
        }
    }

    /** O padrão da rota; null quando a requisição não chegou a um controller (o "/**" é o de arquivos estáticos). */
    static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null) {
            return null;
        }
        String text = pattern.toString();
        return text.startsWith("/api/") ? text : null;
    }

    /**
     * A segurança barra (401, 403) antes de o Spring escolher a rota. Pergunta aqui qual seria: uma rota real fica com o
     * nome dela; um caminho que não existe fica sem rota (e entra como varredura).
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private String lookup(HttpServletRequest request) {
        HandlerMapping mapping = routes.getIfAvailable();
        if (mapping == null) {
            return null;
        }
        try {
            return mapping.getHandler(request) == null ? null : route(request);
        } catch (Exception e) {
            // Rota que existe, com outro método: não é varredura
            return "(outro método)";
        }
    }

    /** Anota na requisição quem está autenticado, enquanto o contexto de segurança ainda existe. */
    static class UserMarker implements HandlerInterceptor {

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
                request.setAttribute(USER, user.id());
            }
            return true;
        }
    }
}
