package com.booksmith.manuscript.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** O modelo das páginas do livro: o que está entre colchetes é para completar e não sai no livro. */
class BookPagesTemplateTest {

    @Test
    void anUntouchedTemplatePrintsNothing() {
        var filled = Manuscripts.BookPages.TEMPLATE.filled();
        assertThat(filled.credits()).isNull();
        assertThat(filled.aboutAuthor()).isNull();
        assertThat(filled.colophon()).isNull();
        assertThat(filled.dedication()).isNull();
        assertThat(filled.epigraph()).isNull();
        assertThat(filled.acknowledgments()).isNull();
        // Na organização do livro ilustrado, as páginas do modelo já aparecem (para mudar de lugar e de fundo)
        assertThat(Manuscripts.BookPages.TEMPLATE.extraKeys()).containsExactly("cover", "credits", "title",
                "dedication", "epigraph", "acknowledgments", "about", "colophon");
        assertThat(filled.extraKeys()).containsExactly("cover", "credits", "title");
    }

    @Test
    void theBackfillOfOlderBooksUsesTheSameTemplate() throws Exception {
        String sql = new String(getClass().getResourceAsStream("/db/migration/V39__book_pages_template_backfill.sql")
                .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        var t = Manuscripts.BookPages.TEMPLATE;
        for (String text : new String[] {t.dedication(), t.epigraph(), t.epigraphSource(), t.acknowledgments(),
                t.aboutAuthor(), t.colophon()}) {
            assertThat(sql).contains("'" + text + "'");
        }
        t.credits().lines().forEach(line -> assertThat(sql).contains(line));
        assertThat(Manuscripts.BookPages.pending(Manuscripts.BookPages.TEMPLATE.credits())).hasSize(6);
    }

    @Test
    void onlyTheCompletedLinesArePrinted() {
        String credits = Manuscripts.BookPages.TEMPLATE.credits()
                .replace("[nome de quem revisou]", "Ana Lima")
                .replace("[nome de quem ilustrou]", "Giulia");
        var pages = new Manuscripts.BookPages(credits, null, null, null, null, null, null);
        assertThat(pages.filled().credits()).isEqualTo("Revisão: Ana Lima\nIlustração: Giulia");
        assertThat(Manuscripts.BookPages.pending(credits)).hasSize(4)
                .contains("Capa: [nome de quem fez a capa]");
        // Limite conhecido: qualquer trecho entre colchetes conta como campo do modelo (a ajuda avisa para não usar)
        assertThat(new Manuscripts.BookPages("Edição revista [2ª]", null, null, null, null, null, null)
                .filled().credits()).isNull();
    }
}
