package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cliente do serviço {@code spellcheck} (LanguageTool próprio, §8.5 etapa 2). Só a API fala com ele; o texto não
 * é guardado lá. Fora do ar → 503 {@code spellcheck_unavailable}: o editor volta ao corretor do navegador.
 */
@Slf4j
@Component
class LanguageToolClient {

    /** Idioma do livro → código do LanguageTool ("en" sozinho não tem corretor ortográfico lá). */
    static final Map<String, String> LANGUAGES = Map.of("pt-BR", "pt-BR", "pt-PT", "pt-PT", "en", "en-US", "es", "es");

    private final RestClient http;
    private final JsonMapper jsonMapper;

    LanguageToolClient(@Value("${booksmith.spellcheck.url:http://localhost:8081}") String url, JsonMapper jsonMapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.http = RestClient.builder().baseUrl(url).requestFactory(factory).build();
        this.jsonMapper = jsonMapper;
    }

    /** Erro encontrado: posição no texto enviado, tipo (spelling, grammar, style), mensagem e sugestões. */
    record Match(int offset, int length, String type, String message, String shortMessage, List<String> replacements,
                 String ruleId) {
    }

    List<Match> check(String text, String language) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("text", text);
        form.add("language", LANGUAGES.getOrDefault(language, "pt-BR"));
        String body;
        try {
            body = http.post().uri("/v2/check").contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().body(String.class);
        } catch (RestClientException e) {
            log.warn("Corretor fora do ar: {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "spellcheck_unavailable",
                    "O corretor está indisponível agora. O do navegador continua funcionando.");
        }
        List<Match> matches = new ArrayList<>();
        for (JsonNode m : jsonMapper.readTree(body).path("matches")) {
            List<String> replacements = new ArrayList<>();
            for (JsonNode r : m.path("replacements")) {
                if (replacements.size() < 5) {
                    replacements.add(r.path("value").stringValue(""));
                }
            }
            JsonNode rule = m.path("rule");
            matches.add(new Match(m.path("offset").intValue(), m.path("length").intValue(),
                    type(rule.path("issueType").stringValue(""), rule.path("category").path("id").stringValue("")),
                    m.path("message").stringValue(""), m.path("shortMessage").stringValue(""), replacements,
                    rule.path("id").stringValue("")));
        }
        return matches;
    }

    /** Ortografia (vermelho), gramática (azul) ou estilo (tracejado): o editor sublinha diferente. */
    static String type(String issueType, String category) {
        if ("misspelling".equals(issueType) || "TYPOS".equals(category)) {
            return "spelling";
        }
        if ("grammar".equals(issueType) || "GRAMMAR".equals(category) || "PUNCTUATION".equals(category)
                || "TYPOGRAPHY".equals(category)) {
            return "grammar";
        }
        return "style";
    }
}
