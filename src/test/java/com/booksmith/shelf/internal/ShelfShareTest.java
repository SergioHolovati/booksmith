package com.booksmith.shelf.internal;

import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShelfShareTest {

    private final ShelfReading reading = mock(ShelfReading.class);
    private final ShelfShare share = new ShelfShare(reading, "https://booksmith.com.br/");

    @Test
    void theWorkPreviewHasTitleSynopsisAndAbsoluteCover() {
        when(reading.visibleCard("o-farol-abc123")).thenReturn(card("/media/covers/x.jpg", "Uma \"guardiã\" <e> o mar"));
        String html = share.page("/estante/obra/o-farol-abc123");
        assertThat(html)
                .contains("<meta property=\"og:title\" content=\"O Farol\">")
                .contains("content=\"https://booksmith.com.br/media/covers/x.jpg\"")
                .contains("<meta property=\"og:url\" content=\"https://booksmith.com.br/estante/obra/o-farol-abc123\">")
                .contains("summary_large_image")
                // O texto do autor sai escapado: nada vira marcação
                .contains("Uma &quot;guardiã&quot; &lt;e&gt; o mar")
                .doesNotContain("<e>")
                .doesNotContain("<script");
    }

    @Test
    void theChapterPreviewSaysWhichChapter() {
        when(reading.visibleCard("o-farol-abc123")).thenReturn(card(null, null));
        when(reading.chapter("o-farol-abc123", 2, null)).thenReturn(new ShelfReading.ChapterView("o-farol-abc123", "O Farol",
                UUID.randomUUID(), "Pena", "12", List.of(), 2, 5, "A tempestade", "<p>x</p>", 1, Instant.now(), 0, false, 0));
        String html = share.page("/estante/obra/o-farol-abc123/2");
        assertThat(html).contains("og:title\" content=\"A tempestade · O Farol\"").contains("Capítulo 2 de 5")
                .contains("twitter:card\" content=\"summary\"").doesNotContain("og:image\"");
    }

    @Test
    void onlyEstantePathsArePreviewed() {
        assertThatThrownBy(() -> share.page("/estante/../admin")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> share.page(null)).isInstanceOf(ApiException.class);
        assertThat(ShelfShare.shorten("a ".repeat(300))).hasSizeLessThanOrEqualTo(200).endsWith("…");
    }

    private static ShelfReading.WorkCard card(String cover, String synopsis) {
        return new ShelfReading.WorkCard(UUID.randomUUID(), "o-farol-abc123", "O Farol", synopsis, cover, "FICTION",
                false, null, "12", List.of(), List.of(), "none", "ongoing", UUID.randomUUID(), "Pena", 3, 1000, 0,
                Instant.now());
    }
}
