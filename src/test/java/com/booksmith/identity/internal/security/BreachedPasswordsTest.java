package com.booksmith.identity.internal.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class BreachedPasswordsTest {

    @Test
    void onlyTheFirstFiveCharactersOfTheHashLeaveTheServer() {
        // SHA-1 de "password", o exemplo da documentação do Have I Been Pwned
        assertThat(BreachedPasswords.sha1("password")).isEqualTo("5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8");
    }

    @Test
    void readsHowManyTimesTheSuffixAppears() {
        String body = "003D68EB55068C33ACE09247EE4C639306B:3\r\n1E4C9B93F3F0682250B6CF8331B7EE68FD8:9659365\r\n"
                + "011053FD0102E94D6AE2F8B83D76FAF94F6:0";
        assertThat(BreachedPasswords.count(body, "1E4C9B93F3F0682250B6CF8331B7EE68FD8")).isEqualTo(9_659_365);
        // Linha de preenchimento (0) e sufixo ausente: não vazou
        assertThat(BreachedPasswords.count(body, "011053FD0102E94D6AE2F8B83D76FAF94F6")).isZero();
        assertThat(BreachedPasswords.count(body, "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF")).isZero();
        assertThat(BreachedPasswords.count(null, "X")).isZero();
    }

    @Test
    void turnedOffOrOutOfReachThePasswordPasses() {
        assertThatCode(() -> new BreachedPasswords("http://127.0.0.1:9", false).requireNotBreached("password"))
                .doesNotThrowAnyException();
        // Serviço fora do ar: não impede o cadastro
        assertThatCode(() -> new BreachedPasswords("http://127.0.0.1:9", true).requireNotBreached("password"))
                .doesNotThrowAnyException();
    }
}
