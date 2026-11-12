package com.booksmith.assist.internal;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.booksmith.common.error.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * A API do Claude (Anthropic), pelo SDK oficial, com saída estruturada: a resposta já vem como
 * {@link IdeaList}. Ligada com {@code booksmith.ai.provider=anthropic} e {@code ANTHROPIC_API_KEY}; os dados que
 * entram no pedido saem do computador. Se o modelo recusar o pedido, a API tenta de novo num modelo reserva
 * escolhido pelo servidor ({@code fallbacks: "default"}).
 */
@Slf4j
class ClaudeIdeas implements IdeaModel {

    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AnthropicClient client;
    private final String model;

    ClaudeIdeas(AiProperties.Anthropic config) {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalStateException("booksmith.ai.provider=anthropic exige ANTHROPIC_API_KEY");
        }
        this.client = AnthropicOkHttpClient.builder().apiKey(config.apiKey()).build();
        this.model = config.model();
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public boolean local() {
        return false;
    }

    @Override
    public List<Idea> ideas(Prompt prompt) {
        List<Idea> ideas = new ArrayList<>();
        ask(prompt, IdeaList.class).forEach(list -> ideas.addAll(list.suggestions()));
        return ideas;
    }

    @Override
    public List<Finding> findings(Prompt prompt) {
        List<Finding> findings = new ArrayList<>();
        ask(prompt, FindingList.class).forEach(list -> findings.addAll(list.findings()));
        return findings;
    }

    /** Um pedido com saída estruturada no formato {@code type}; devolve cada bloco de texto já convertido. */
    private <T> List<T> ask(Prompt prompt, Class<T> type) {
        StructuredMessageCreateParams<T> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16_000L)
                .system(prompt.system())
                .addUserMessage(prompt.user())
                .putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .outputConfig(type)
                .build();
        try {
            var message = client.messages().create(params);
            if (message.stopReason().map(StopReason.REFUSAL::equals).orElse(false)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ai_refused",
                        "A IA não quis responder a este pedido. Tente com outras palavras.");
            }
            return message.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(typed -> typed.text())
                    .toList();
        } catch (AnthropicException e) {
            log.warn("API do Claude falhou: {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ai_unavailable",
                    "A IA está indisponível agora. Tente de novo em instantes.").causedBy(e);
        }
    }
}
