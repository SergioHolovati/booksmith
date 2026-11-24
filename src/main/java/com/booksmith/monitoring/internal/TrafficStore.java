package com.booksmith.monitoring.internal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Guarda no Redis o que o {@link TrafficCounter} juntou (a cada 30 segundos) e monta o resumo das últimas horas.
 * Cada chave vive {@link #KEEP}: o IP é dado pessoal, então só fica o tempo de enxergar um ataque. Sem Redis, a
 * janela se perde e a API segue normal.
 */
@Slf4j
@Component
public class TrafficStore {

    static final Duration KEEP = Duration.ofHours(49);
    static final int MAX_HOURS = 48;
    /** Nomes por ranking por hora no Redis; passou disso, o resto entra em "(outros)". */
    static final int MEMBERS_PER_HOUR = 2_000;
    static final int TOP_PER_HOUR = 200;
    private static final int TOP = 15;

    private final TrafficCounter counter;
    private final StringRedisTemplate redis;
    private final Clock clock;

    TrafficStore(TrafficCounter counter, StringRedisTemplate redis, Clock clock) {
        this.counter = counter;
        this.redis = redis;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${booksmith.monitoring.flush-interval:PT30S}")
    public synchronized void flush() {
        Map<TrafficCounter.Cell, Long> cells = counter.drain();
        if (cells.isEmpty()) {
            return;
        }
        try {
            Set<String> keys = new HashSet<>();
            Map<String, Long> sizes = new HashMap<>();
            cells.forEach((cell, delta) -> {
                if (cell.ranking()) {
                    rank(cell, delta, sizes);
                } else {
                    redis.opsForHash().increment(cell.key(), cell.field(), delta);
                }
                keys.add(cell.key());
            });
            keys.forEach(key -> redis.expire(key, KEEP));
        } catch (RuntimeException e) {
            log.warn("Monitoramento: não deu para gravar {} contadores no Redis ({})", cells.size(), e.getMessage());
        }
    }

    private void rank(TrafficCounter.Cell cell, long delta, Map<String, Long> sizes) {
        ZSetOperations<String, String> zset = redis.opsForZSet();
        long size = sizes.computeIfAbsent(cell.key(), k -> {
            Long card = zset.zCard(k);
            return card == null ? Long.valueOf(0) : card;
        });
        String member = cell.field();
        if (size >= MEMBERS_PER_HOUR && zset.score(cell.key(), member) == null) {
            member = TrafficCounter.OTHERS;
        }
        Double after = zset.incrementScore(cell.key(), member, delta);
        if (after != null && after == delta) {
            sizes.put(cell.key(), size + 1);
        }
    }

    /** O resumo das últimas {@code hours} horas (1 a 48), já com o que ainda estava na memória. */
    public TrafficReport report(int hours) {
        flush();
        int span = Math.clamp(hours, 1, MAX_HOURS);
        Instant thisHour = clock.instant().truncatedTo(ChronoUnit.HOURS);

        List<TrafficReport.HourPoint> series = new ArrayList<>();
        long requests = 0;
        long client = 0;
        long limited = 0;
        long server = 0;
        long millis = 0;
        Map<String, long[]> routes = new HashMap<>();
        Map<String, long[]> addresses = new HashMap<>();
        Map<String, Long> users = new HashMap<>();
        Map<String, Long> probes = new HashMap<>();
        Map<String, Long> incidents = new LinkedHashMap<>();

        for (int i = span - 1; i >= 0; i--) {
            Instant start = thisHour.minus(i, ChronoUnit.HOURS);
            String hour = TrafficCounter.hour(start);
            Map<String, Long> counts = hash(TrafficKeys.counts(hour));
            long total = counts.getOrDefault("total", 0L);
            long hourClient = counts.getOrDefault("client", 0L);
            long hourLimited = counts.getOrDefault("limited", 0L);
            long hourServer = counts.getOrDefault("server", 0L);
            series.add(new TrafficReport.HourPoint(start, total, hourClient, hourLimited, hourServer));
            requests += total;
            client += hourClient;
            limited += hourLimited;
            server += hourServer;
            millis += counts.getOrDefault("ms", 0L);

            hash(TrafficKeys.routes(hour)).forEach((route, n) -> routes.computeIfAbsent(route, r -> new long[3])[0] += n);
            hash(TrafficKeys.routeErrors(hour)).forEach((route, n) -> routes.computeIfAbsent(route, r -> new long[3])[1] += n);
            hash(TrafficKeys.routeMillis(hour)).forEach((route, n) -> routes.computeIfAbsent(route, r -> new long[3])[2] += n);
            top(TrafficKeys.ips(hour), TOP_PER_HOUR).forEach((ip, n) -> addresses.computeIfAbsent(ip, a -> new long[2])[0] += n);
            top(TrafficKeys.suspiciousIps(hour), TOP_PER_HOUR)
                    .forEach((ip, n) -> addresses.computeIfAbsent(ip, a -> new long[2])[1] += n);
            top(TrafficKeys.users(hour), -1).forEach((user, n) -> users.merge(user, n, Long::sum));
            top(TrafficKeys.probes(hour), TOP_PER_HOUR).forEach((path, n) -> probes.merge(path, n, Long::sum));
            hash(TrafficKeys.incidents(hour)).forEach((kind, n) -> incidents.merge(kind, n, Long::sum));
        }

        var totals = new TrafficReport.Totals(requests, client, limited, server, requests == 0 ? 0 : millis / requests,
                (int) users.keySet().stream().filter(u -> !TrafficCounter.OTHERS.equals(u)).count(),
                (int) addresses.keySet().stream().filter(a -> !TrafficCounter.OTHERS.equals(a)).count());
        List<TrafficReport.RouteStat> routeStats = routes.entrySet().stream()
                .map(e -> new TrafficReport.RouteStat(e.getKey(), e.getValue()[0], e.getValue()[1],
                        e.getValue()[0] == 0 ? 0 : e.getValue()[2] / e.getValue()[0]))
                .sorted(Comparator.comparingLong(TrafficReport.RouteStat::requests).reversed())
                .limit(TOP * 2L)
                .toList();
        List<TrafficReport.IpStat> ipStats = addresses.entrySet().stream()
                .map(e -> new TrafficReport.IpStat(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.comparingLong(TrafficReport.IpStat::suspicious)
                        .thenComparingLong(TrafficReport.IpStat::requests).reversed())
                .limit(TOP)
                .toList();
        return new TrafficReport(span, totals, series, routeStats, ipStats, ranked(users), ranked(probes), incidents);
    }

    private Map<String, Long> hash(String key) {
        Map<String, Long> values = new HashMap<>();
        redis.<String, String>opsForHash().entries(key).forEach((field, value) -> values.put(field, Long.parseLong(value)));
        return values;
    }

    /** Os primeiros de um ranking (limit -1: todos). */
    private Map<String, Long> top(String key, int limit) {
        Map<String, Long> values = new HashMap<>();
        Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeWithScores(key, 0, limit < 0 ? -1 : limit - 1);
        if (tuples != null) {
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                Double score = tuple.getScore();
                values.put(tuple.getValue(), score == null ? 0L : score.longValue());
            }
        }
        return values;
    }

    private static List<TrafficReport.Ranked> ranked(Map<String, Long> values) {
        return values.entrySet().stream()
                .map(e -> new TrafficReport.Ranked(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(TrafficReport.Ranked::count).reversed())
                .limit(TOP)
                .toList();
    }
}
