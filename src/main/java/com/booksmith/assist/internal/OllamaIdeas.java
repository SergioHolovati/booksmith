package com.booksmith.assist.internal;

import com.booksmith.common.error.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Modelo local pelo Ollama ({@code POST /api/chat}): nada sai do computador. Pede a resposta já no formato
 * (JSON Schema em {@code format}) e sem o modo "pensando" do modelo, que só atrasaria ideias curtas.
 */
@Slf4j
class OllamaIdeas implements IdeaModel {

    /** O formato que o Ollama impõe à resposta: a lista de ideias, cada uma com título e texto. */
    static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "required", List.of("suggestions"),
            "properties", Map.of("suggestions", Map.of(
                    "type", "array",
                    "items", Map.of(
                            "type", "object",
                            "required", List.of("title", "text"),
                            "properties", Map.of(
                                    "title", Map.of("type", "string"),
                                    "text", Map.of("type", "string"))))));

    private final RestClient http;
    private final JsonMapper jsonMapper;
    private final AiProperties.Ollama config;

    OllamaIdeas(AiProperties.Ollama config, JsonMapper jsonMapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(config.timeout());
        this.http = RestClient.builder().baseUrl(config.url()).requestFactory(factory).build();
        this.jsonMapper = jsonMapper;
        this.config = config;
    }

    @Override
    public String provider() {
        return "ollama";
    }

    @Override
    public String model() {
        return config.model();
    }

    @Override
    public boolean local() {
        return true;
    }

    @Override
    public List<Idea> ideas(Prompt prompt) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("model", config.model());
        body.put("stream", false);
        body.put("think", false);
        body.put("keep_alive", config.keepAlive());
        body.set("format", jsonMapper.valueToTree(SCHEMA));
        body.putObject("options").put("temperature", 0.6).put("num_ctx", 8192);
        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", prompt.system());
        messages.addObject().put("role", "user").put("content", prompt.user());
        String response;
        try {
            response = http.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON)
                    .body(jsonMapper.writeValueAsString(body)).retrieve().body(String.class);
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Modelo {} não está no Ollama: {}", config.model(), e.getMessage());
            throw unavailable("O modelo de IA ainda não foi baixado neste computador.").causedBy(e);
        } catch (RestClientException e) {
            log.warn("Ollama fora do ar: {}", e.getMessage());
            throw unavailable("A IA não está ligada agora. Tente de novo em instantes.").causedBy(e);
        }
        return parse(response, jsonMapper);
    }

    /** A resposta do Ollama traz o JSON das ideias como texto em {@code message.content}. */
    static List<Idea> parse(String response, JsonMapper jsonMapper) {
        try {
            String content = jsonMapper.readTree(response).path("message").path("content").stringValue("");
            JsonNode ideas = jsonMapper.readTree(content).path("suggestions");
            return ideas.valueStream()
                    .map(i -> new Idea(i.path("title").stringValue(""), i.path("text").stringValue("")))
                    .toList();
        } catch (JacksonException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ai_bad_answer",
                    "A IA respondeu num formato inesperado. Tente de novo.").causedBy(e);
        }
    }

    static ApiException unavailable(String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ai_unavailable", message);
    }
}
