package com.booksmith.assist.internal;

import com.booksmith.common.error.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Locale;

/**
 * Escolhe o modelo pela configuração {@code booksmith.ai.provider}. Sem a chave da Anthropic, a API sobe assim
 * mesmo, com a IA desligada (o resto do produto não depende dela).
 */
@Slf4j
@Configuration
class IdeaModelConfig {

    @Bean
    IdeaModel ideaModel(AiProperties properties) {
        String provider = properties.provider() == null ? "off" : properties.provider().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "anthropic" -> {
                String key = properties.anthropic().apiKey();
                if (key == null || key.isBlank()) {
                    log.warn("IA desligada: booksmith.ai.provider=anthropic, mas ANTHROPIC_API_KEY está vazia");
                    yield new Disabled();
                }
                yield new ClaudeIdeas(properties.anthropic());
            }
            case "off" -> new Disabled();
            default -> throw new IllegalStateException("booksmith.ai.provider desconhecido: " + provider);
        };
    }

    /** Sem IA configurada: a tela esconde os botões; se alguém chamar a API mesmo assim, 503. */
    static final class Disabled implements IdeaModel {

        @Override
        public String provider() {
            return "off";
        }

        @Override
        public String model() {
            return "";
        }

        @Override
        public boolean local() {
            return true;
        }

        @Override
        public List<Idea> ideas(Prompt prompt) {
            throw disabled();
        }

        @Override
        public List<Finding> findings(Prompt prompt) {
            throw disabled();
        }

        @Override
        public Answer answer(Prompt prompt) {
            throw disabled();
        }

        private static ApiException disabled() {
            return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ai_disabled",
                    "As sugestões de IA estão desligadas neste servidor.");
        }
    }
}
