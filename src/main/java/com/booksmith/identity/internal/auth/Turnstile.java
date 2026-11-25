package com.booksmith.identity.internal.auth;

import com.booksmith.common.error.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;

/**
 * Desafio anti-robô da Cloudflare (Turnstile), pedido no login só depois de {@value LoginGuard#CAPTCHA_AFTER}
 * senhas erradas na mesma conta. Quem digita a senha certa de primeira nunca vê o desafio. Sem a chave secreta
 * ({@code TURNSTILE_SECRET_KEY}), fica desligado.
 *
 * <p>Se a Cloudflare não responder, o login segue: o bloqueio de 15 minutos depois de 5 erros continua valendo.
 */
@Slf4j
@Component
public class Turnstile {

    private final RestClient http;
    private final JsonMapper jsonMapper;
    private final String siteKey;
    private final String secretKey;

    Turnstile(@Value("${booksmith.login-protection.turnstile-url:https://challenges.cloudflare.com}") String url,
              @Value("${booksmith.login-protection.turnstile-site-key:}") String siteKey,
              @Value("${booksmith.login-protection.turnstile-secret-key:}") String secretKey,
              JsonMapper jsonMapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().baseUrl(url).requestFactory(factory).build();
        this.jsonMapper = jsonMapper;
        this.siteKey = siteKey == null ? "" : siteKey.trim();
        this.secretKey = secretKey == null ? "" : secretKey.trim();
    }

    public boolean enabled() {
        return !secretKey.isEmpty() && !siteKey.isEmpty();
    }

    /** A chave pública do widget (vai para o app); null quando desligado. */
    public String siteKey() {
        return enabled() ? siteKey : null;
    }

    /** @throws ApiException 403 {@code captcha_required} sem o desafio ou com um desafio recusado */
    void require(String token) {
        if (!enabled()) {
            return;
        }
        if (token == null || token.isBlank()) {
            throw required();
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("secret", secretKey);
        form.add("response", token);
        String body;
        try {
            body = http.post().uri("/turnstile/v0/siteverify").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(String.class);
        } catch (RestClientException e) {
            log.warn("Turnstile indisponível; o login seguiu sem o desafio: {}", e.getMessage());
            return;
        }
        if (body == null || !jsonMapper.readTree(body).path("success").booleanValue()) {
            throw required();
        }
    }

    private ApiException required() {
        return new ApiException(HttpStatus.FORBIDDEN, "captcha_required",
                "Por segurança, confirme que você não é um robô para continuar.", Map.of("siteKey", siteKey));
    }
}
