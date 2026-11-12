package com.booksmith.identity.internal.mfa;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;

/**
 * Códigos do app autenticador (TOTP, RFC 6238): 6 números a cada 30 segundos, HMAC-SHA1, como o Google
 * Authenticator, o Microsoft Authenticator e o 1Password esperam. Feito aqui, sem biblioteca: são poucas linhas
 * e evitam uma dependência a mais na parte mais sensível do login.
 */
final class Totp {

    static final int STEP_SECONDS = 30;
    static final int DIGITS = 6;
    /** Aceita um passo antes e um depois: relógio do celular um pouco adiantado ou atrasado. */
    static final int WINDOW = 1;

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** Segredo novo de 160 bits, em base32 (o formato que os apps leem). */
    static String newSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32(bytes);
    }

    /** O link que o app abre (e o QR code mostra): {@code otpauth://totp/Booksmith:email?secret=...&issuer=Booksmith}. */
    static String uri(String secret, String account) {
        String label = URLEncoder.encode("Booksmith:" + account, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=Booksmith&algorithm=SHA1&digits="
                + DIGITS + "&period=" + STEP_SECONDS;
    }

    static long step(Instant now) {
        return now.getEpochSecond() / STEP_SECONDS;
    }

    /**
     * O passo em que o código vale (dentro da janela), se vale.
     *
     * @param code 6 números (espaços já tirados)
     */
    static OptionalLong match(String secret, String code, Instant now) {
        if (code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return OptionalLong.empty();
        }
        byte[] key = unbase32(secret);
        long current = step(now);
        for (long s = current - WINDOW; s <= current + WINDOW; s++) {
            if (constantTimeEquals(code(key, s), code)) {
                return OptionalLong.of(s);
            }
        }
        return OptionalLong.empty();
    }

    static String code(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%0" + DIGITS + "d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    static byte[] unbase32(String text) {
        String clean = text.replace("=", "").replace(" ", "").toUpperCase(java.util.Locale.ROOT);
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Segredo inválido");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) (buffer >> (bits - 8)));
                bits -= 8;
            }
        }
        return out.array();
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }
}
