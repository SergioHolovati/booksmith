package com.booksmith.common.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Criptografa segredos que o Booksmith precisa ler de volta e guarda no banco (segredo do app autenticador, acesso
 * ao Google Drive): AES-256-GCM, com IV aleatório a cada vez. A chave fica fora do banco ({@code CRYPTO_KEY}); quem
 * copiar só o banco não lê os segredos.
 *
 * <p>Formato: base64(IV de 12 bytes + texto cifrado com a etiqueta de 16 bytes).
 */
@Component
public class SecretBox {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    /**
     * @param configuredKey 32 bytes em base64; vazio em desenvolvimento: a chave é derivada do segredo do JWT
     */
    public SecretBox(@Value("${booksmith.crypto.key:}") String configuredKey,
                     @Value("${booksmith.security.jwt-secret}") String jwtSecret) {
        this.key = new SecretKeySpec(keyBytes(configuredKey, jwtSecret), "AES");
    }

    public String seal(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao criptografar", e);
        }
    }

    /** @throws IllegalStateException se o texto foi alterado ou a chave é outra */
    public String open(String sealed) {
        try {
            byte[] all = Base64.getDecoder().decode(sealed);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Segredo ilegível: chave trocada ou dado alterado", e);
        }
    }

    private static byte[] keyBytes(String configuredKey, String jwtSecret) {
        if (configuredKey != null && !configuredKey.isBlank()) {
            byte[] bytes = Base64.getDecoder().decode(configuredKey.strip());
            if (bytes.length != 32) {
                throw new IllegalStateException("CRYPTO_KEY precisa ter 32 bytes em base64.");
            }
            return bytes;
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(("booksmith-crypto|" + jwtSecret).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
