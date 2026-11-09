package com.booksmith.identity.internal.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Limite por IP, em memória (vale por instância). Rotas de autenticação têm limite mais baixo contra força bruta.
 * O IP vem de {@code getRemoteAddr()}: atrás do ALB, {@code server.forward-headers-strategy} resolve o IP real
 * sem confiar em um X-Forwarded-For enviado pelo cliente. Migrar para Bucket4j + Redis com várias instâncias (§22.1).
 */
class RateLimitingFilter extends OncePerRequestFilter {

    private final RateLimitProperties limits;
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    RateLimitingFilter(RateLimitProperties limits) {
        this.limits = limits;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean authRoute = request.getRequestURI().startsWith("/api/v1/auth/");
        String key = (authRoute ? "auth:" : "api:") + request.getRemoteAddr();
        Bucket bucket = buckets.get(key, k -> newBucket(authRoute ? limits.authPerMinute() : limits.generalPerMinute()));

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/problem+json");
        response.getWriter().write("""
                {"status":429,"title":"Too Many Requests","code":"rate_limited",\
                "detail":"Muitas requisições. Aguarde um instante e tente de novo."}""");
    }

    private static Bucket newBucket(long perMinute) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1)).build())
                .build();
    }
}
