package com.booksmith.monitoring.internal;

/** As chaves do Redis por hora ({@code yyyyMMddHH}, horário de Brasília). */
final class TrafficKeys {

    private TrafficKeys() {
    }

    /** Hash: total, ms (soma), client (4xx), limited (429), server (5xx). */
    static String counts(String hour) {
        return "mon:" + hour + ":count";
    }

    /** Hash: rota ("GET /api/v1/books/{id}") → requisições. */
    static String routes(String hour) {
        return "mon:" + hour + ":route";
    }

    /** Hash: rota → soma dos milissegundos. */
    static String routeMillis(String hour) {
        return "mon:" + hour + ":route-ms";
    }

    /** Hash: rota → respostas 4xx e 5xx. */
    static String routeErrors(String hour) {
        return "mon:" + hour + ":route-err";
    }

    /** Ranking: IP → requisições. */
    static String ips(String hour) {
        return "mon:" + hour + ":ip";
    }

    /** Ranking: IP → respostas suspeitas (401, 403, 429, 404 sem rota). */
    static String suspiciousIps(String hour) {
        return "mon:" + hour + ":ip-bad";
    }

    /** Ranking: conta → requisições. */
    static String users(String hour) {
        return "mon:" + hour + ":user";
    }

    /** Ranking: caminho inexistente → tentativas. */
    static String probes(String hour) {
        return "mon:" + hour + ":probe";
    }

    /** Hash: tipo do {@code SecurityIncident} → ocorrências. */
    static String incidents(String hour) {
        return "mon:" + hour + ":incident";
    }
}
