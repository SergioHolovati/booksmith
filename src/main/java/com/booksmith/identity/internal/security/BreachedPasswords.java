package com.booksmith.identity.internal.security;

import com.booksmith.common.error.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Recusa senhas que já apareceram em vazamentos de outros sites (Have I Been Pwned). São as primeiras que um robô
 * testa. Só os 5 primeiros caracteres do hash SHA-1 da senha saem do servidor (k-anonimato): o serviço devolve
 * centenas de hashes com esse começo, e a comparação é feita aqui. Com preenchimento ({@code Add-Padding}), nem o
 * tamanho da resposta revela algo.
 *
 * <p>Se o serviço estiver fora do ar, a senha passa (as outras regras continuam valendo): ninguém fica sem conseguir
 * criar conta por causa de um serviço de fora.
 */
@Slf4j
@Component
public class BreachedPasswords {

    private static final int PREFIX = 5;

    private final RestClient http;
    private final boolean enabled;

    public BreachedPasswords(@Value("${booksmith.login-protection.breached-passwords-url:https://api.pwnedpasswords.com}") String url,
                             @Value("${booksmith.login-protection.breached-passwords:true}") boolean enabled) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.http = RestClient.builder().baseUrl(url).requestFactory(factory)
                .defaultHeader("User-Agent", "Booksmith-password-check")
                .defaultHeader("Add-Padding", "true")
                .build();
        this.enabled = enabled;
    }

    /** @throws ApiException 422 {@code password_breached} */
    public void requireNotBreached(String password) {
        if (!enabled || password == null || password.isEmpty()) {
            return;
        }
        if (timesSeen(password) > 0) {
            throw ApiException.unprocessable("password_breached",
                    "Essa senha já apareceu em vazamentos de dados de outros sites, e é das primeiras que robôs "
                            + "testam. Escolha outra.");
        }
    }

    /** Quantas vezes a senha aparece nos vazamentos conhecidos; 0 também quando o serviço não responde. */
    long timesSeen(String password) {
        String hash = sha1(password);
        String body;
        try {
            body = http.get().uri("/range/{prefix}", hash.substring(0, PREFIX)).retrieve().body(String.class);
        } catch (RestClientException e) {
            log.warn("Conferência de senha vazada indisponível; a senha passou sem ela: {}", e.getMessage());
            return 0;
        }
        return count(body, hash.substring(PREFIX));
    }

    /** Na resposta, cada linha é {@code SUFIXO:VEZES}; as do preenchimento vêm com 0. */
    static long count(String body, String suffix) {
        if (body == null) {
            return 0;
        }
        for (String line : body.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(suffix)) {
                try {
                    return Long.parseLong(line.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    static String sha1(String password) {
        try {
            // SHA-1 é o formato do Have I Been Pwned (consulta por k-anonimato); a senha é guardada em bcrypt
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(password.getBytes(StandardCharsets.UTF_8)); // nosemgrep: java.lang.security.audit.crypto.use-of-sha1.use-of-sha1
            return HexFormat.of().formatHex(digest).toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 indisponível", e);
        }
    }
}
