package com.booksmith.monitoring.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * O resumo das últimas horas para o backoffice. Os rankings juntam os primeiros de cada hora, então são
 * aproximados quando uma hora tem mais de {@value TrafficStore#TOP_PER_HOUR} IPs.
 *
 * @param incidents tipo do {@code SecurityIncident} → ocorrências no período
 */
public record TrafficReport(int hours, Totals totals, List<HourPoint> series, List<RouteStat> routes,
                            List<IpStat> addresses, List<Ranked> users, List<Ranked> probes,
                            Map<String, Long> incidents) {

    /**
     * @param averageMillis tempo médio de resposta da API
     * @param activeUsers   contas diferentes que usaram o app
     * @param addresses     IPs diferentes
     */
    public record Totals(long requests, long clientErrors, long limited, long serverErrors, long averageMillis,
                         int activeUsers, int addresses) {
    }

    /** Uma hora do gráfico (o começo da hora). */
    public record HourPoint(Instant hour, long requests, long clientErrors, long limited, long serverErrors) {
    }

    /** @param errors respostas 4xx e 5xx */
    public record RouteStat(String route, long requests, long errors, long averageMillis) {
    }

    /** @param suspicious respostas 401, 403, 429 e 404 sem rota */
    public record IpStat(String ip, long requests, long suspicious) {
    }

    public record Ranked(String name, long count) {
    }
}
