package com.booksmith.identity.internal.auth;

import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TurnstileTest {

    @Test
    void withoutTheKeysTheChallengeIsOff() {
        Turnstile off = new Turnstile("http://127.0.0.1:9", "", "", new JsonMapper());
        assertThat(off.enabled()).isFalse();
        assertThat(off.siteKey()).isNull();
        assertThatCode(() -> off.require(null)).doesNotThrowAnyException();
    }

    @Test
    void turnedOnTheLoginNeedsTheChallengeAnswered() {
        Turnstile on = new Turnstile("http://127.0.0.1:9", "site-key", "secret-key", new JsonMapper());
        assertThat(on.siteKey()).isEqualTo("site-key");
        assertThatThrownBy(() -> on.require(" "))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("captcha_required"));
        // Cloudflare fora do ar: o login segue (o bloqueio depois de 5 erros continua valendo)
        assertThatCode(() -> on.require("token")).doesNotThrowAnyException();
    }
}
