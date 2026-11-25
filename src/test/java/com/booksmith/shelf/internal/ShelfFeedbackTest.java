package com.booksmith.shelf.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShelfFeedbackTest {

    @Test
    void quotesTheStartOfTheCommentedParagraph() {
        String html = "<p class=\"blk blk-b_1\">Primeiro.</p>\n<p class=\"indent blk blk-b_2\">O <em>farol</em> &amp; o mar.</p>";
        assertThat(ShelfFeedback.quote(html, "b_2")).isEqualTo("O farol & o mar.");
        assertThat(ShelfFeedback.quote(html, "b_1")).isEqualTo("Primeiro.");
        // O parágrafo saiu ao republicar
        assertThat(ShelfFeedback.quote(html, "b_9")).isNull();
        // Prefixo de outro id não confunde
        assertThat(ShelfFeedback.quote("<p class=\"blk blk-b_10\">Dez.</p>", "b_1")).isNull();
        assertThat(ShelfFeedback.quote("<p class=\"blk blk-b_3\">" + "palavra ".repeat(40) + "</p>", "b_3")).endsWith("…");
    }
}
