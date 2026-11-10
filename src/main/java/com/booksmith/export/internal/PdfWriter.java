package com.booksmith.export.internal;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.Set;

import static com.booksmith.export.internal.BookHtml.escape;

/**
 * PDF de leitura (§13.1), para compartilhar e ler na tela: A5, Literata embutida, sumário com número de
 * página e marcadores clicáveis. HTML + CSS Paged Media em processo (OpenHTMLtoPDF). O PDF de gráfica
 * (PDF/X, CMYK, sangria) é outro motor, na Fase 2.
 */
final class PdfWriter {

    /** Formatos de capa que o PDF embute. WebP fica de fora: o livro sai sem a página de capa. */
    private static final Set<String> COVER_TYPES = Set.of("image/jpeg", "image/png");

    private static final String FONT = "Literata";

    private PdfWriter() {
    }

    static byte[] write(ExportBook book) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            font(builder, "Literata-Regular.ttf", 400, FontStyle.NORMAL);
            font(builder, "Literata-Italic.ttf", 400, FontStyle.ITALIC);
            font(builder, "Literata-Bold.ttf", 700, FontStyle.NORMAL);
            font(builder, "Literata-BoldItalic.ttf", 700, FontStyle.ITALIC);
            builder.withHtmlContent(html(book), null);
            builder.toStream(bytes);
            builder.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    static String html(ExportBook book) {
        StringBuilder bookmarks = new StringBuilder();
        StringBuilder toc = new StringBuilder();
        StringBuilder chapters = new StringBuilder();
        for (int i = 0; i < book.chapters().size(); i++) {
            ExportBook.Chapter chapter = book.chapters().get(i);
            String id = "chapter-" + (i + 1);
            String title = escape(chapter.title());
            bookmarks.append("<bookmark name=\"%s\" href=\"#%s\"/>\n".formatted(title, id));
            toc.append("<li><a href=\"#%s\">%s</a></li>\n".formatted(id, title));
            chapters.append("""
                    <section class="chapter" id="%s">
                    <h1>%s</h1>
                    %s</section>
                    """.formatted(id, title, chapter.html(imageId -> dataUri(book, imageId))));
        }
        String cover = "";
        if (book.cover() != null && COVER_TYPES.contains(book.cover().contentType())) {
            cover = "<div class=\"cover\"><img src=\"data:%s;base64,%s\" alt=\"Capa\"/></div>\n"
                    .formatted(book.cover().contentType(), Base64.getEncoder().encodeToString(book.cover().bytes()));
        }
        String notice = book.fanfic() ? "<div class=\"front\">" + EpubWriter.fanNotice(book) + "</div>\n" : "";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="%1$s" xml:lang="%1$s">
                <head>
                <meta charset="UTF-8"/>
                <title>%2$s</title>
                <meta name="author" content="%3$s"/>
                <style>%4$s%10$s</style>
                <bookmarks>
                %5$s</bookmarks>
                </head>
                <body>
                %6$s<div class="title-page">
                <p class="book-title">%2$s</p>
                <p class="book-author">%3$s</p>
                </div>
                %7$s<div class="toc">
                <h1>Sumário</h1>
                <ol>
                %8$s</ol>
                </div>
                %9$s</body>
                </html>
                """.formatted(book.language(), escape(book.title()), escape(book.author()), CSS, bookmarks, cover,
                notice, toc, chapters, cover.isEmpty() ? "" : "@page :first { margin: 0; }");
    }

    /** Ilustração como data URI; WebP o motor de PDF não lê, então sai só a legenda. */
    static String dataUri(ExportBook book, String imageId) {
        var image = book.illustrations().get(imageId);
        if (image == null || !COVER_TYPES.contains(image.contentType())) {
            return null;
        }
        return "data:%s;base64,%s".formatted(image.contentType(), Base64.getEncoder().encodeToString(image.bytes()));
    }

    private static void font(PdfRendererBuilder builder, String file, int weight, FontStyle style) {
        ClassPathResource resource = new ClassPathResource("export/fonts/" + file);
        builder.useFont(() -> {
            try {
                return resource.getInputStream();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, FONT, weight, style, true);
    }

    /** Página A5, margem generosa, número no pé só nos capítulos. A capa (se houver) ocupa a primeira página inteira. */
    private static final String CSS = """
            @page { size: 148mm 210mm; margin: 20mm 17mm 22mm 17mm; }
            @page main { @bottom-center { content: counter(page); font-family: Literata; font-size: 9pt; color: #555; } }
            body { font-family: Literata; font-size: 11pt; line-height: 1.45; color: #1a1a1a; }
            .cover { page-break-after: always; }
            .cover img { display: block; width: 148mm; height: 209mm; }
            .title-page, .toc, .front { page-break-after: always; }
            .title-page { text-align: center; padding-top: 55mm; }
            .book-title { font-size: 22pt; line-height: 1.2; margin: 0 0 12mm; }
            .book-author { font-size: 13pt; margin: 0; }
            .toc h1 { margin-top: 10mm; }
            .toc ol { list-style: none; padding: 0; margin: 0; }
            .toc li { margin: 0 0 3mm; }
            .toc a { color: #1a1a1a; text-decoration: none; }
            .toc a::after { content: leader('.') target-counter(attr(href), page); }
            .front p { margin: 0 0 4mm; }
            .chapter { page: main; page-break-before: always; }
            h1 { font-size: 17pt; font-weight: normal; text-align: center; margin: 25mm 0 12mm; }
            h2 { font-size: 13pt; margin: 6mm 0 3mm; }
            h3, h4 { font-size: 11.5pt; margin: 5mm 0 2mm; }
            p { margin: 0; text-align: justify; orphans: 2; widows: 2; }
            p.indent { text-indent: 1.5em; }
            figure.illustration { margin: 6mm 0; text-align: center; page-break-inside: avoid; }
            figure.illustration img { max-width: 100%; max-height: 140mm; }
            figure.illustration figcaption { font-size: 9.5pt; font-style: italic; margin-top: 2mm; text-align: center; }
            p.scene-break { text-align: center; margin: 4mm 0; letter-spacing: 2mm; }
            blockquote { margin: 3mm 8mm; font-style: italic; }
            blockquote em { font-style: normal; }
            """;
}
