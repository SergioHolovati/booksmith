package com.booksmith.assist.internal;

import com.booksmith.assist.internal.IdeaModel.Idea;
import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OllamaIdeasTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void readsTheIdeasFromTheMessageContent() {
        String response = """
                {"model": "qwen3:8b", "message": {"role": "assistant",
                 "content": "{ \\"suggestions\\": [ {\\"title\\": \\"Medo do mar\\", \\"text\\": \\"E se...\\"} ] }"},
                 "done": true}
                """;

        assertThat(OllamaIdeas.parse(response, mapper)).containsExactly(new Idea("Medo do mar", "E se..."));
    }

    @Test
    void anAnswerOutOfFormatIsABadGateway() {
        assertThatThrownBy(() -> OllamaIdeas.parse("{\"message\": {\"content\": \"não é JSON\"}}", mapper))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("ai_bad_answer");
                    assertThat(e.getStatus().value()).isEqualTo(502);
                });
    }

    @Test
    void ollamaOffIsUnavailable() {
        var ollama = new OllamaIdeas(new AiProperties.Ollama("http://127.0.0.1:9", "qwen3:8b", "30m",
                Duration.ofSeconds(2)), mapper);

        assertThatThrownBy(() -> ollama.ideas(new IdeaModel.Prompt("s", "u")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ai_unavailable"));
        assertThat(ollama.local()).isTrue();
        assertThat(List.of(ollama.provider(), ollama.model())).containsExactly("ollama", "qwen3:8b");
    }
}
