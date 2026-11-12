package com.booksmith.identity.internal.mfa;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TotpTest {

    /** O segredo dos vetores de teste da RFC 6238 (SHA-1): "12345678901234567890". */
    private static final byte[] RFC_KEY = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesTheRfc6238Vectors() {
        // A RFC dá 8 dígitos (94287082, 07081804, 14050471, 89005924); com 6, são os 6 últimos
        assertThat(Totp.code(RFC_KEY, 59 / 30)).isEqualTo("287082");
        assertThat(Totp.code(RFC_KEY, 1111111109L / 30)).isEqualTo("081804");
        assertThat(Totp.code(RFC_KEY, 1111111111L / 30)).isEqualTo("050471");
        assertThat(Totp.code(RFC_KEY, 1234567890L / 30)).isEqualTo("005924");
    }

    @Test
    void base32GoesBothWays() {
        assertThat(Totp.base32(RFC_KEY)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(Totp.unbase32("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")).isEqualTo(RFC_KEY);
        assertThat(Totp.newSecret()).hasSize(32).matches("[A-Z2-7]+");
    }

    @Test
    void acceptsOneStepOfClockDriftAndNothingElse() {
        String secret = Totp.base32(RFC_KEY);
        Instant now = Instant.ofEpochSecond(1234567890L);
        long step = Totp.step(now);

        assertThat(Totp.match(secret, Totp.code(RFC_KEY, step), now)).hasValue(step);
        assertThat(Totp.match(secret, Totp.code(RFC_KEY, step - 1), now)).hasValue(step - 1);
        assertThat(Totp.match(secret, Totp.code(RFC_KEY, step + 1), now)).hasValue(step + 1);
        assertThat(Totp.match(secret, Totp.code(RFC_KEY, step - 2), now)).isEmpty();
        assertThat(Totp.match(secret, "12345", now)).isEmpty();
        assertThat(Totp.match(secret, "abcdef", now)).isEmpty();
    }

    @Test
    void uriIsWhatAuthenticatorAppsRead() {
        assertThat(Totp.uri("ABC234", "lia moreau@example.com"))
                .isEqualTo("otpauth://totp/Booksmith%3Alia%20moreau%40example.com?secret=ABC234&issuer=Booksmith"
                        + "&algorithm=SHA1&digits=6&period=30");
    }
}
