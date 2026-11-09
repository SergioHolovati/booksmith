package com.booksmith.identity.internal.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * @param jwtSecret          segredo HMAC dos tokens (mínimo 32 caracteres; nunca versionar)
 * @param accessTokenTtl     validade do token de acesso
 * @param sessionTtl         validade da sessão (renovada a cada refresh); longa para quem escreve no celular
 * @param frontendUrl        URL do app web, usada nos redirecionamentos e links de e-mail
 * @param corsAllowedOrigins origens liberadas no CORS
 */
@ConfigurationProperties("booksmith.security")
public record SecurityProperties(
        String jwtSecret,
        Duration accessTokenTtl,
        Duration sessionTtl,
        String frontendUrl,
        List<String> corsAllowedOrigins
) {
    public SecurityProperties {
        if (jwtSecret == null || jwtSecret.length() < 32) {
            throw new IllegalStateException(
                    "booksmith.security.jwt-secret precisa ter pelo menos 32 caracteres (defina JWT_SECRET).");
        }
    }
}
