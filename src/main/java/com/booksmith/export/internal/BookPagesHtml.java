package com.booksmith.export.internal;

import com.booksmith.manuscript.api.Manuscripts.BookPages;

import java.util.ArrayList;
import java.util.List;

import static com.booksmith.export.internal.BookHtml.escape;

/**
 * As páginas antes e depois da história em XHTML, na ordem de um livro impresso: créditos (verso da folha de
 * rosto), dedicatória e epígrafe antes do sumário; agradecimentos e "sobre o autor" depois do último capítulo.
 * O mesmo HTML no ePub e no PDF.
 */
final class BookPagesHtml {

    private BookPagesHtml() {
    }

    /**
     * Uma página extra do livro.
     *
     * @param id      nome do arquivo no ePub (sem extensão) e âncora no PDF
     * @param title   o que aparece no sumário e no título da página XHTML
     * @param inToc   entra no sumário (agradecimentos e "sobre"; dedicatória e epígrafe não, como nos livros)
     */
    record Page(String id, String title, String html, boolean inToc) {
    }

    /** Créditos, dedicatória e epígrafe, as que existirem. */
    static List<Page> front(BookPages pages) {
        List<Page> front = new ArrayList<>();
        if (pages.credits() != null) {
            front.add(new Page("credits", "Créditos", """
                    <section epub:type="copyright-page" class="credits">
                    %s</section>
                    """.formatted(paragraphs(pages.credits())), false));
        }
        if (pages.dedication() != null) {
            front.add(new Page("dedication", "Dedicatória", """
                    <section epub:type="dedication" class="dedication">
                    %s</section>
                    """.formatted(paragraphs(pages.dedication())), false));
        }
        if (pages.epigraph() != null) {
            String source = pages.epigraphSource() == null
                    ? "" : "<p class=\"epigraph-source\">— " + escape(pages.epigraphSource()) + "</p>\n";
            front.add(new Page("epigraph", "Epígrafe", """
                    <section epub:type="epigraph" class="epigraph">
                    <div class="epigraph-text">
                    %s</div>
                    %s</section>
                    """.formatted(paragraphs(pages.epigraph()), source), false));
        }
        return front;
    }

    /** Agradecimentos e a página sobre quem escreveu (com o título escolhido, ou o nome de quem escreveu). */
    static List<Page> back(BookPages pages, String author) {
        List<Page> back = new ArrayList<>();
        if (pages.acknowledgments() != null) {
            back.add(new Page("acknowledgments", "Agradecimentos", """
                    <section epub:type="acknowledgments" class="back-page">
                    <h1>Agradecimentos</h1>
                    %s</section>
                    """.formatted(paragraphs(pages.acknowledgments())), true));
        }
        if (pages.aboutAuthor() != null) {
            String title = pages.aboutAuthorTitle() == null ? author : pages.aboutAuthorTitle();
            back.add(new Page("about-author", title, """
                    <section class="back-page about-author">
                    <h1>%s</h1>
                    %s</section>
                    """.formatted(escape(title), paragraphs(pages.aboutAuthor())), true));
        }
        return back;
    }

    /** Cada linha com texto vira um parágrafo; linhas em branco só separam. */
    static String paragraphs(String text) {
        StringBuilder html = new StringBuilder();
        for (String line : text.split("\n")) {
            if (!line.isBlank()) {
                html.append("<p>").append(escape(line.strip())).append("</p>\n");
            }
        }
        return html.toString();
    }
}
