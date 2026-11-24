package com.booksmith.monitoring.internal;

import com.booksmith.common.alerts.SecurityIncident;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Conta as requisições em memória, numa "janela" que o {@link TrafficStore} esvazia no Redis a cada 30 segundos.
 * A requisição só soma num contador (nada de rede no caminho dela). Cada ranking (IP, conta, caminho inexistente)
 * aceita até {@value #MEMBERS_PER_WINDOW} nomes por janela; o resto vira {@value #OTHERS}, para um ataque com
 * milhares de IPs ou caminhos não encher a memória.
 */
@Component
class TrafficCounter {

    static final int MEMBERS_PER_WINDOW = 500;
    static final String OTHERS = "(outros)";
    static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(BRASILIA);
    private static final int MAX_PATH = 100;

    /** Uma célula: um campo de um hash do Redis ou um nome de um ranking (sorted set). */
    record Cell(String key, String field, boolean ranking) {
    }

    private static final class Window {
        final Map<Cell, LongAdder> cells = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> members = new ConcurrentHashMap<>();
    }

    private final AtomicReference<Window> window = new AtomicReference<>(new Window());
    private final Clock clock;

    TrafficCounter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Uma requisição que terminou.
     *
     * @param route o padrão da rota ({@code /api/v1/books/{id}}); null quando não chegou a uma rota (barrada antes ou
     *              caminho que não existe)
     * @param path  o caminho pedido (só entra no ranking de caminhos inexistentes)
     * @param user  a conta, se a requisição estava autenticada
     */
    void record(Instant at, String method, String route, String path, int status, long millis, String ip, UUID user) {
        String hour = hour(at);
        String counts = TrafficKeys.counts(hour);
        add(counts, "total", false, 1);
        add(counts, "ms", false, millis);
        if (status == 429) {
            add(counts, "limited", false, 1);
        } else if (status >= 500) {
            add(counts, "server", false, 1);
        } else if (status >= 400) {
            add(counts, "client", false, 1);
        }

        String label = route != null ? method + " " + route : "(sem rota) " + status;
        add(TrafficKeys.routes(hour), label, false, 1);
        add(TrafficKeys.routeMillis(hour), label, false, millis);
        if (status >= 400) {
            add(TrafficKeys.routeErrors(hour), label, false, 1);
        }

        if (ip != null) {
            add(TrafficKeys.ips(hour), ip, true, 1);
            if (suspicious(route, status)) {
                add(TrafficKeys.suspiciousIps(hour), ip, true, 1);
            }
        }
        if (user != null) {
            add(TrafficKeys.users(hour), user.toString(), true, 1);
        }
        if (route == null && (status == 404 || status == 401 || status == 403) && path != null) {
            add(TrafficKeys.probes(hour), clean(path), true, 1);
        }
    }

    /** Os sinais de segurança (login bloqueado, 429, erro 500, imagem recusada...) entram na mesma hora. */
    @EventListener
    public void on(SecurityIncident incident) {
        add(TrafficKeys.incidents(hour(clock.instant())), incident.kind().name(), false, 1);
    }

    /**
     * Respostas que, repetidas, apontam um ataque: sem login (401), sem permissão (403), barrada pelo limite (429) ou
     * um caminho que não existe (404 sem rota: quem procura falhas testa caminhos conhecidos de outros sistemas).
     */
    static boolean suspicious(String route, int status) {
        return status == 401 || status == 403 || status == 429 || status == 404 && route == null;
    }

    /** Troca a janela por uma vazia e devolve o que ela juntou. */
    Map<Cell, Long> drain() {
        Window old = window.getAndSet(new Window());
        Map<Cell, Long> totals = new HashMap<>();
        old.cells.forEach((cell, adder) -> totals.put(cell, adder.sum()));
        return totals;
    }

    static String hour(Instant at) {
        return HOUR.format(at);
    }

    private void add(String key, String field, boolean ranking, long delta) {
        Window current = window.get();
        Cell cell = new Cell(key, field, ranking);
        LongAdder adder = current.cells.get(cell);
        if (adder == null) {
            if (ranking && current.members.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet()
                    > MEMBERS_PER_WINDOW) {
                cell = new Cell(key, OTHERS, true);
            }
            adder = current.cells.computeIfAbsent(cell, c -> new LongAdder());
        }
        adder.add(delta);
    }

    /** Caminho curto e só com caracteres visíveis (vai para a tela do admin). */
    static String clean(String path) {
        StringBuilder out = new StringBuilder(Math.min(path.length(), MAX_PATH));
        for (int i = 0; i < path.length() && out.length() < MAX_PATH; i++) {
            char c = path.charAt(i);
            out.append(c >= 0x21 && c <= 0x7e ? c : '?');
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}
