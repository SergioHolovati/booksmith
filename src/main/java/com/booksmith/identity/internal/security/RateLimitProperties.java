package com.booksmith.identity.internal.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limites por IP. As rotas de autenticação têm limite baixo contra força bruta; em desenvolvimento ele é
 * maior, porque os testes de integração cadastram muitos usuários a partir do mesmo IP.
 */
@ConfigurationProperties("booksmith.security.rate-limit")
record RateLimitProperties(long generalPerMinute, long authPerMinute) {
}
