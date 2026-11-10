package com.booksmith.support.internal;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Contexto técnico que o app manda junto com o pedido. Só estes campos são guardados, e cada um é cortado no
 * tamanho máximo: o servidor nunca aceita texto livre aqui, para o conteúdo do manuscrito não vazar por engano.
 *
 * @param route          tela onde a pessoa estava (ex.: {@code /books/{id}/chapters/{id}})
 * @param appVersion     versão do app
 * @param userAgent      navegador e sistema
 * @param online         se o aparelho estava conectado
 * @param screen         tamanho da tela ("390x844")
 * @param language       idioma do navegador
 * @param traceIds       últimos {@code X-Request-Trace-Id} com erro, para achar no log
 * @param chapterId      capítulo aberto, se a pessoa estava no editor
 * @param autosaveStatus estado do salvamento automático no editor
 */
public record SupportContext(String route, String appVersion, String userAgent, Boolean online, String screen,
                             String language, List<String> traceIds, UUID chapterId, String autosaveStatus) {

    private static final Pattern TRACE_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    static final int MAX_TRACE_IDS = 10;

    /** Corta cada campo no tamanho máximo e descarta trace ids malformados. */
    SupportContext clean() {
        List<String> traces = traceIds == null ? List.of() : traceIds.stream()
                .filter(t -> t != null && TRACE_ID.matcher(t).matches())
                .distinct()
                .limit(MAX_TRACE_IDS)
                .toList();
        return new SupportContext(cut(route, 300), cut(appVersion, 40), cut(userAgent, 400), online, cut(screen, 20),
                cut(language, 20), traces, chapterId, cut(autosaveStatus, 40));
    }

    static SupportContext empty() {
        return new SupportContext(null, null, null, null, null, null, List.of(), null, null);
    }

    private static String cut(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.strip();
        return text.length() <= max ? text : text.substring(0, max);
    }
}
