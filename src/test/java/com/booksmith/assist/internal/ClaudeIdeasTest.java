package com.booksmith.assist.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeIdeasTest {

    @Test
    void onlyModelsWithServerFallbackGetTheFallbackField() {
        assertThat(ClaudeIdeas.supportsFallback("claude-opus-5-5")).isTrue();
        assertThat(ClaudeIdeas.supportsFallback("claude-sonnet-5-5")).isTrue();
        assertThat(ClaudeIdeas.supportsFallback("claude-fable-5-2")).isTrue();
        // o Haiku recusaria o pedido inteiro com o campo fallbacks
        assertThat(ClaudeIdeas.supportsFallback("claude-haiku-5-5")).isFalse();
        assertThat(ClaudeIdeas.supportsFallback("claude-sonnet-5")).isFalse();
    }
}
