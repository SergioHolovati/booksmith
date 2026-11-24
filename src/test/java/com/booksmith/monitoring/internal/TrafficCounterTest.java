package com.booksmith.monitoring.internal;

import com.booksmith.common.alerts.SecurityIncident;
import com.booksmith.identity.api.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TrafficCounterTest {

    /** 15h UTC = 12h em Brasília. */
    private static final Instant NOW = Instant.parse("2026-11-24T15:20:00Z");
    private static final String HOUR = "2026112412";

    private final TrafficCounter counter = new TrafficCounter(Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void eachRequestCountsByRouteStatusAddressAndAccount() {
        UUID lia = UUID.randomUUID();
        counter.record(NOW, "GET", "/api/v1/books/{id}", "/api/v1/books/1", 200, 40, "10.0.0.1", lia);
        counter.record(NOW, "GET", "/api/v1/books/{id}", "/api/v1/books/2", 404, 10, "10.0.0.1", lia);
        counter.record(NOW, "POST", null, "/api/v1/auth/login", 429, 1, "10.0.0.2", null);
        counter.record(NOW, "GET", "/api/v1/profile", "/api/v1/profile", 500, 9, "10.0.0.2", null);

        Map<String, Long> cells = flat(counter.drain());
        assertThat(cells).containsEntry("mon:" + HOUR + ":count/total", 4L)
                .containsEntry("mon:" + HOUR + ":count/ms", 60L)
                .containsEntry("mon:" + HOUR + ":count/client", 1L)
                .containsEntry("mon:" + HOUR + ":count/limited", 1L)
                .containsEntry("mon:" + HOUR + ":count/server", 1L)
                .containsEntry("mon:" + HOUR + ":route/GET /api/v1/books/{id}", 2L)
                .containsEntry("mon:" + HOUR + ":route-err/GET /api/v1/books/{id}", 1L)
                .containsEntry("mon:" + HOUR + ":route-ms/GET /api/v1/books/{id}", 50L)
                .containsEntry("mon:" + HOUR + ":route/(sem rota) 429", 1L)
                .containsEntry("mon:" + HOUR + ":ip/10.0.0.1", 2L)
                .containsEntry("mon:" + HOUR + ":ip-bad/10.0.0.2", 1L)
                .containsEntry("mon:" + HOUR + ":user/" + lia, 2L);
        // Livro que não existe (404 numa rota da API) é uso normal, não sinal de ataque
        assertThat(cells).doesNotContainKey("mon:" + HOUR + ":ip-bad/10.0.0.1");
        // A janela recomeça vazia
        assertThat(counter.drain()).isEmpty();
    }

    @Test
    void aPathThatDoesNotExistIsAProbeAndASuspiciousAddress() {
        counter.record(NOW, "GET", null, "/api/v1/../../etc/passwd\n<script>", 404, 2, "203.0.113.9", null);

        Map<String, Long> cells = flat(counter.drain());
        assertThat(cells).containsEntry("mon:" + HOUR + ":probe//api/v1/../../etc/passwd?<script>", 1L)
                .containsEntry("mon:" + HOUR + ":ip-bad/203.0.113.9", 1L);
        assertThat(TrafficCounter.clean("/a".repeat(80))).hasSize(100);

        // Sem login, a segurança responde 401 antes do 404: caminho sem rota também conta como varredura
        counter.record(NOW, "GET", null, "/api/v1/.env", 401, 1, "203.0.113.9", null);
        counter.record(NOW, "GET", "/api/v1/books", "/api/v1/books", 401, 1, "203.0.113.9", null);
        Map<String, Long> again = flat(counter.drain());
        assertThat(again).containsEntry("mon:" + HOUR + ":probe//api/v1/.env", 1L)
                .containsEntry("mon:" + HOUR + ":ip-bad/203.0.113.9", 2L)
                .doesNotContainKey("mon:" + HOUR + ":probe//api/v1/books");
    }

    @Test
    void aFloodOfAddressesFoldsIntoOthers() {
        for (int i = 0; i < TrafficCounter.MEMBERS_PER_WINDOW + 50; i++) {
            counter.record(NOW, "GET", "/api/v1/books", "/api/v1/books", 200, 1, "10.1." + i / 256 + "." + i % 256, null);
        }
        Map<TrafficCounter.Cell, Long> cells = counter.drain();
        List<TrafficCounter.Cell> ips = cells.keySet().stream().filter(c -> c.key().endsWith(":ip")).toList();
        assertThat(ips).hasSize(TrafficCounter.MEMBERS_PER_WINDOW + 1);
        assertThat(cells.get(new TrafficCounter.Cell("mon:" + HOUR + ":ip", TrafficCounter.OTHERS, true))).isEqualTo(50L);
    }

    @Test
    void securityIncidentsAreCountedInTheSameHour() {
        counter.on(new SecurityIncident(SecurityIncident.Kind.LOGIN_LOCKED, "x"));
        counter.on(new SecurityIncident(SecurityIncident.Kind.LOGIN_LOCKED, "y"));
        assertThat(flat(counter.drain())).containsEntry("mon:" + HOUR + ":incident/LOGIN_LOCKED", 2L);
    }

    @Test
    void theFilterRecordsTheRoutePatternAndTheSignedInAccount() throws Exception {
        UUID lia = UUID.randomUUID();
        @SuppressWarnings("unchecked")
        ObjectProvider<HandlerMapping> noRoutes = mock(ObjectProvider.class);
        var filter = new TrafficFilter(counter, Clock.fixed(NOW, ZoneOffset.UTC), noRoutes);
        var request = new MockHttpServletRequest("GET", "/api/v1/books/123");
        request.setRemoteAddr("10.0.0.7");
        var response = new MockHttpServletResponse();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(lia, "lia", "Lia", "s1"), null, List.of()));

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                new TrafficFilter.UserMarker().preHandle(request, response, new Object());
                req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/books/{id}");
                ((MockHttpServletResponse) res).setStatus(200);
            }
        });

        Map<String, Long> cells = flat(counter.drain());
        assertThat(cells).containsEntry("mon:" + HOUR + ":route/GET /api/v1/books/{id}", 1L)
                .containsEntry("mon:" + HOUR + ":user/" + lia, 1L)
                .containsEntry("mon:" + HOUR + ":ip/10.0.0.7", 1L);
    }

    @Test
    void onlyApiRoutesCountAsRoutes() {
        var request = new MockHttpServletRequest("GET", "/api/v1/nada");
        assertThat(TrafficFilter.route(request)).isNull();
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/**");
        assertThat(TrafficFilter.route(request)).isNull();
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/books");
        assertThat(TrafficFilter.route(request)).isEqualTo("/api/v1/books");
    }

    private static Map<String, Long> flat(Map<TrafficCounter.Cell, Long> cells) {
        return cells.entrySet().stream().collect(Collectors.toMap(e -> e.getKey().key() + "/" + e.getKey().field(),
                Map.Entry::getValue));
    }
}
