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
