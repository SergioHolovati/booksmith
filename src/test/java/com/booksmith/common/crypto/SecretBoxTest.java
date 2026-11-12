package com.booksmith.common.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretBoxTest {

    private static final String JWT = "test-secret-with-at-least-32-characters!!";

    @Test
    void sealsAndOpensWithADifferentIvEachTime() {
        SecretBox box = new SecretBox("", JWT);
        String a = box.seal("JBSWY3DPEHPK3PXP");
        String b = box.seal("JBSWY3DPEHPK3PXP");

        assertThat(a).isNotEqualTo(b).doesNotContain("JBSWY3DPEHPK3PXP");
        assertThat(box.open(a)).isEqualTo("JBSWY3DPEHPK3PXP");
        assertThat(box.open(b)).isEqualTo("JBSWY3DPEHPK3PXP");
    }

    @Test
    void anotherKeyOrAlteredTextCannotOpen() {
        String sealed = new SecretBox("", JWT).seal("segredo");
        String otherKey = Base64.getEncoder().encodeToString(new byte[32]);

        assertThatThrownBy(() -> new SecretBox(otherKey, JWT).open(sealed)).isInstanceOf(IllegalStateException.class);
        byte[] bytes = Base64.getDecoder().decode(sealed);
        bytes[bytes.length - 1] ^= 1;
        assertThatThrownBy(() -> new SecretBox("", JWT).open(Base64.getEncoder().encodeToString(bytes)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SecretBox("curta", JWT)).isInstanceOf(RuntimeException.class);
    }
}
