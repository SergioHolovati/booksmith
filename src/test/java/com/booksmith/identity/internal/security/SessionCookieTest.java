package com.booksmith.identity.internal.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCookieTest {

    private static SessionCookie cookie(String frontendUrl) {
        return new SessionCookie(new SecurityProperties("test-secret-with-at-least-32-characters!!", Duration.ofHours(1),
                Duration.ofDays(30), frontendUrl, List.of(frontendUrl)));
    }

    @Test
    void theRefreshTokenGoesInAnHttpOnlyStrictCookieOnlyForTheSessionRoutes() {
        var response = new MockHttpServletResponse();

        cookie("https://booksmith.com.br").write(response, "abc");

        String header = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header).startsWith("booksmith_refresh=abc;")
                .contains("Path=/api/v1/auth", "Max-Age=2592000", "Secure", "HttpOnly", "SameSite=Strict");
    }

    @Test
    void notSecureWhenTheAppRunsOnPlainHttp() {
        // Em desenvolvimento, pelo IP da rede local, o navegador não guardaria um cookie Secure
        var response = new MockHttpServletResponse();

        cookie("http://localhost:4200").write(response, "abc");

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).doesNotContain("Secure").contains("HttpOnly");
    }

    @Test
    void withoutANewRefreshTokenTheCookieStaysAndClearingExpiresIt() {
        var response = new MockHttpServletResponse();
        var cookie = cookie("https://booksmith.com.br");

        cookie.write(response, null);
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();

        cookie.clear(response);
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).startsWith("booksmith_refresh=;").contains("Max-Age=0");
    }
}
