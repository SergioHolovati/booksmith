package com.booksmith.export.internal;

import com.booksmith.manuscript.api.BookLayout;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.List;
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
            builder.withHtmlContent(book.pictureBook() ? PictureBookHtml.html(book) : html(book), null);
            builder.toStream(bytes);
            builder.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return book.layout().hasBleed() ? withPrintBoxes(bytes.toByteArray(), book.layout()) : bytes.toByteArray();
    }

    private static final float POINTS_PER_MM = 72f / 25.4f;

    /**
     * Miolo com sangria: cada página ganha o TrimBox (onde a gráfica corta) e o BleedBox (a página inteira). A
     * sangria fica na borda de fora: à direita nas páginas ímpares (direita do livro aberto), à esquerda nas pares.
     */
    static byte[] withPrintBoxes(byte[] pdf, BookLayout layout) {
        try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            float bleed = (float) BookLayout.BLEED_MM * POINTS_PER_MM;
            float width = (float) layout.widthMm() * POINTS_PER_MM;
            float height = (float) layout.heightMm() * POINTS_PER_MM;
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                var page = document.getPage(i);
                boolean right = i % 2 == 0;
                page.setBleedBox(page.getMediaBox());
                page.setTrimBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(right ? 0 : bleed, bleed, width, height));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String html(ExportBook book) {
        StringBuilder bookmarks = new StringBuilder();
        StringBuilder toc = new StringBuilder();
        StringBuilder chapters = new StringBuilder();
        BookHtml.PageIllustration pages = pageIllustrations(book);
        for (int i = 0; i < book.chapters().size(); i++) {
            ExportBook.Chapter chapter = book.chapters().get(i);
            String id = "chapter-" + (i + 1);
            String title = escape(chapter.title());
            if (chapter.partTitle() != null) {
                String partId = "part-" + (i + 1);
                String part = escape(chapter.partTitle());
                bookmarks.append("<bookmark name=\"%s\" href=\"#%s\"/>\n".formatted(part, partId));
                toc.append("<li class=\"toc-part\"><a href=\"#%s\">%s</a></li>\n".formatted(partId, part));
                chapters.append("<section class=\"part-page\" id=\"%s\"><h1 class=\"part-title\">%s</h1></section>\n"
                        .formatted(partId, part));
            }
            List<BookHtml.Section> sections = chapter.sections();
            if (sections.isEmpty()) {
                bookmarks.append("<bookmark name=\"%s\" href=\"#%s\"/>\n".formatted(title, id));
            } else {
                bookmarks.append("<bookmark name=\"%s\" href=\"#%s\">\n".formatted(title, id));
            }
            toc.append("<li><a href=\"#%s\">%s</a></li>\n".formatted(id, title));
            // Os subcapítulos: no sumário, recuados embaixo do capítulo, e nos marcadores do PDF
            for (BookHtml.Section section : sections) {
                String sectionId = BookHtml.sectionId(id, section.number());
                String sectionTitle = escape(section.title());
                bookmarks.append("<bookmark name=\"%s\" href=\"#%s\"/>\n".formatted(sectionTitle, sectionId));
                toc.append("<li class=\"toc-section\"><a href=\"#%s\">%s</a></li>\n".formatted(sectionId, sectionTitle));
            }
            if (!sections.isEmpty()) {
                bookmarks.append("</bookmark>\n");
            }
            chapters.append("""
                    <section class="chapter" id="%s">
                    <h1>%s</h1>
                    %s</section>
                    """.formatted(id, title, sceneBreaks(chapter.html(imageId -> dataUri(book, imageId), id, pages), book.layout())));
        }
        String cover = "";
        if (hasCoverPage(book)) {
            cover = "<div class=\"cover\"><img src=\"data:%s;base64,%s\" alt=\"Capa\"/></div>\n"
                    .formatted(book.cover().contentType(), Base64.getEncoder().encodeToString(book.cover().bytes()));
        }
        for (BookPagesHtml.Page extra : BookPagesHtml.back(book.pages(), book.author(), authorPhoto(book))) {
            String title = escape(extra.title());
            bookmarks.append("<bookmark name=\"%s\" href=\"#%s\"/>\n".formatted(title, extra.id()));
            toc.append("<li><a href=\"#%s\">%s</a></li>\n".formatted(extra.id(), title));
            chapters.append("<div class=\"back\" id=\"%s\">%s</div>\n".formatted(extra.id(), extra.html()));
        }
        String notice = (book.fanfic() ? "<div class=\"front\">" + EpubWriter.fanNotice(book) + "</div>\n" : "")
                + BookPagesHtml.front(book.pages(), book.pages().isbnPrint(), book.title()).stream()
                .map(extra -> "<div class=\"front\" id=\"%s\">%s</div>\n".formatted(extra.id(), extra.html()))
                .collect(java.util.stream.Collectors.joining());
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
                notice, toc, chapters, layoutCss(book.layout()));
    }

    /** O "* * *" do HTML comum vira o ornamento escolhido na diagramação. */
    static String sceneBreaks(String html, BookLayout layout) {
        String ornament = switch (layout.sceneBreak()) {
            case "asterism" -> "⁂";
            case "diamond" -> "◆";
            case "dots" -> "• • •";
            case "space" -> "&#160;";
            default -> "* * *";
        };
        return html.replace("<p class=\"scene-break\">* * *</p>", "<p class=\"scene-break\">" + ornament + "</p>");
    }

    /**
     * O que muda com a diagramação, depois do CSS fixo (ganha dele): página, margens, letra, entrelinha e a
     * abertura de capítulo. Margens em mm: em cima, dos lados, embaixo.
     */
    static String layoutCss(BookLayout layout) {
        int[] margins = marginsMm(layout);
        double leading = switch (layout.leading()) {
            case "compact" -> 1.3;
            case "airy" -> 1.6;
            default -> 1.45;
        };
        String chapter = switch (layout.chapterStyle()) {
            case "modern" -> ".chapter > h1 { text-align: left; font-size: 22pt; margin: 6mm 0 10mm; padding-bottom: 3mm; "
                    + "border-bottom: 0.5pt solid #1a1a1a; }";
            case "simple" -> ".chapter > h1 { font-size: 15pt; margin: 0 0 8mm; }";
            default -> "";
        };
        double[] page = pageBoxMm(layout);
        String width = number(page[0]);
        // Com sangria, a página cresce na borda de fora e em cima e embaixo; o texto fica no mesmo lugar do corte
        double bleed = layout.hasBleed() ? BookLayout.BLEED_MM : 0;
        String sides = bleed == 0 ? "" : """
                @page :right { margin-left: %1$smm; margin-right: %2$smm; }
                @page :left { margin-left: %2$smm; margin-right: %1$smm; }
                """.formatted(number(margins[1]), number(margins[1] + bleed));
        // A capa tem a sua página com nome, sem margem. Com "@page :first { margin: 0 }", o OpenHTMLtoPDF calculava
        // a largura do texto pela primeira página (a da capa) e o texto do livro inteiro saía pela margem direita.
        return """
                @page { size: %1$smm %2$smm; margin: %3$smm %4$smm %5$smm %4$smm; }
                %10$sbody { font-size: %6$spt; line-height: %7$s; }
                @page cover { margin: 0; }
                .cover { page: cover; }
                .cover img { width: %1$smm; height: %8$smm; }
                .chapter > h1 + p.indent { text-indent: 0; }
                %9$s""".formatted(width, number(page[1]), number(margins[0] + bleed), number(margins[1]),
                number(margins[2] + bleed), number(layout.fontSize()), number(leading), number(page[1] - 1), chapter, sides);
    }

    /** Margens da página em mm: em cima, dos lados, embaixo. */
    static int[] marginsMm(BookLayout layout) {
        return switch (layout.margins()) {
            case "narrow" -> new int[] {16, 13, 18};
            case "wide" -> new int[] {24, 21, 26};
            default -> new int[] {20, 17, 22};
        };
    }

    /** Largura da área de texto em mm: a página menos as margens dos lados. */
    static double textWidthMm(BookLayout layout) {
        return layout.widthMm() - 2.0 * marginsMm(layout)[1];
    }

    /** Altura máxima de uma ilustração no PDF, em mm (a mesma do CSS de {@code figure.illustration img}). */
    static final int ILLUSTRATION_MAX_HEIGHT_MM = 140;

    /** Altura da área de texto em mm: a página menos as margens de cima e de baixo. */
    static double textHeightMm(BookLayout layout) {
        int[] margins = marginsMm(layout);
        return layout.heightMm() - margins[0] - margins[2];
    }

    /**
     * A página inteira (largura, altura) em mm: onde a ilustração até a borda se espalha. Com sangria, o corte mais
     * {@link BookLayout#BLEED_MM} na borda de fora e em cima e embaixo.
     */
    static double[] pageBoxMm(BookLayout layout) {
        double bleed = layout.hasBleed() ? BookLayout.BLEED_MM : 0;
        return new double[] {layout.widthMm() + bleed, layout.heightMm() + 2 * bleed};
    }

    /**
     * As ilustrações com página própria, desenhadas no tamanho exato da página: só ela dentro das margens (inteira,
     * centralizada), até a borda (cobrindo a página, sem número) e dupla (começa numa página da esquerda e continua
     * na da direita). Medidas em mm, calculadas aqui ({@link Placement#fit}) porque o motor de PDF não tem
     * {@code object-fit}. Formato que o PDF não lê (WebP): o desenho comum, só com a legenda.
     */
    static BookHtml.PageIllustration pageIllustrations(ExportBook book) {
        java.util.Map<String, int[]> sizes = new java.util.HashMap<>();
        return (imageId, placement, caption) -> {
            String url = dataUri(book, imageId);
            if (url == null) {
                return null;
            }
            int[] px = sizes.computeIfAbsent(imageId, id -> PrintCheck.pixels(book.illustrations().get(id).bytes()));
            if (px[0] == 0 || px[1] == 0) {
                return null;
            }
            Placement.Fit fit = placement.fit(px[0], px[1], book.layout(), !caption.isEmpty());
            String alt = escape(caption.isEmpty() ? "Ilustração" : caption);
            if (placement == Placement.PAGE) {
                double top = (fit.boxHeightMm() - fit.heightMm()) / 2;
                return "<div class=\"illustration-page\" style=\"padding-top: %smm\"><img src=\"%s\" alt=\"%s\" style=\"width: %smm; height: %smm\"/>%s</div>\n"
                        .formatted(mm(top), url, alt, mm(fit.widthMm()), mm(fit.heightMm()),
                                caption.isEmpty() ? "" : "<p class=\"illustration-caption\">" + escape(caption) + "</p>");
            }
            double[] page = pageBoxMm(book.layout());
            if (placement == Placement.BLEED) {
                return bleedPage("bleed-page", page, url, alt, fit, fit.offsetXMm());
            }
            // Dupla: a metade esquerda numa página par (esquerda do livro aberto), a direita na seguinte
            return bleedPage("bleed-page spread-left", page, url, alt, fit, fit.offsetXMm())
                    + bleedPage("bleed-page", page, url, alt, fit, fit.offsetXMm() + page[0]);
        };
    }

    /** Uma página sem margem com a imagem cobrindo tudo; {@code offsetX}: quanto da imagem fica para fora à esquerda. */
    private static String bleedPage(String css, double[] page, String url, String alt, Placement.Fit fit, double offsetX) {
        // 1 mm a menos de altura: na altura exata, o arredondamento do motor empurra uma página em branco
        return "<div class=\"%s\"><div class=\"bleed-box\" style=\"width: %smm; height: %smm\"><img src=\"%s\" alt=\"%s\" style=\"width: %smm; height: %smm; margin-left: -%smm; margin-top: -%smm\"/></div></div>\n"
                .formatted(css, mm(page[0]), mm(page[1] - 1), url, alt, mm(fit.widthMm()), mm(fit.heightMm()), mm(offsetX),
                        mm(fit.offsetYMm()));
    }

    /** Milímetros com uma casa, com ponto (CSS). */
    private static String mm(double value) {
        return String.valueOf(Math.round(value * 10) / 10.0);
    }

    /** 148 → "148"; 152.4 → "152.4" (CSS não aceita vírgula). */
    private static String number(double value) {
        // Em centésimos inteiros: 20 + 3,2 dá 23,200000000000003 em double
        long hundredths = Math.round(value * 100);
        return java.math.BigDecimal.valueOf(hundredths, 2).stripTrailingZeros().toPlainString();
    }

    /** A primeira página do PDF é a capa (JPEG ou PNG); WebP fica de fora. */
    static boolean hasCoverPage(ExportBook book) {
        // Com sangria é o miolo para a gráfica: a capa vai em arquivo próprio (a capa aberta). O livro ilustrado
        // não põe a capa no PDF do miolo
        return !book.pictureBook() && book.cover() != null && COVER_TYPES.contains(book.cover().contentType()) && !book.layout().hasBleed();
    }

    /** O motor de PDF lê o formato (JPEG e PNG; WebP não). */
    static boolean embeds(String contentType) {
        return COVER_TYPES.contains(contentType);
    }

    /** A foto de quem escreveu, como data URI (null sem foto, ou num formato que o PDF não lê). */
    static String authorPhoto(ExportBook book) {
        var id = book.pages().authorPhotoId();
        return id == null ? null : dataUri(book, id.toString());
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
            body { margin: 0; font-family: Literata; font-size: 11pt; line-height: 1.45; color: #1a1a1a; }
            .cover { page-break-after: always; }
            .cover img { display: block; width: 148mm; height: 209mm; }
            .title-page, .toc, .front { page-break-after: always; }
            .title-page { text-align: center; padding-top: 55mm; }
            .book-title { font-size: 22pt; line-height: 1.2; margin: 0 0 12mm; }
            .book-author { font-size: 13pt; margin: 0; }
            .title-page p { text-align: center; }
            .toc h1 { margin-top: 10mm; }
            .toc ol { list-style: none; padding: 0; margin: 0; }
            .toc li { margin: 0 0 3mm; }
            .toc a { color: #1a1a1a; text-decoration: none; }
            .toc a::after { content: leader('.') target-counter(attr(href), page); }
            .front p { margin: 0 0 4mm; }
            .credits { padding-top: 110mm; font-size: 9pt; }
            .credits p { margin: 0 0 2mm; text-align: left; }
            .credits p.isbn { margin-top: 4mm; }
            .dedication { padding-top: 50mm; margin-left: 35mm; font-style: italic; }
            .dedication p, p.epigraph-source { text-align: right; }
            .epigraph { padding-top: 50mm; margin-left: 30mm; }
            .epigraph-text p { font-style: italic; text-align: left; margin: 0 0 2mm; }
            p.epigraph-source { font-size: 10pt; }
            .back { page: main; page-break-before: always; }
            .back-page p { text-indent: 1.5em; margin: 0 0 1mm; }
            .chapter { page: main; page-break-before: always; }
            h1 { font-size: 17pt; font-weight: normal; text-align: center; margin: 25mm 0 12mm; }
            h2 { font-size: 13pt; margin: 6mm 0 3mm; }
            h3, h4 { font-size: 11.5pt; margin: 5mm 0 2mm; }
            p { margin: 0; text-align: justify; orphans: 2; widows: 2; }
            p.indent { text-indent: 1.5em; }
            .part-page { page-break-before: always; page-break-after: always; text-align: center; padding-top: 60mm; }
            h1.part-title { font-size: 20pt; margin: 0; }
            .half-title { page-break-after: always; padding-top: 60mm; text-align: center; font-size: 15pt; }
            .catalog-card { margin-top: 6mm; border: 0.5pt solid #555; padding: 3mm 4mm; font-size: 8pt; }
            .catalog-card p { text-align: left; text-indent: 0; margin: 0; }
            .author-photo { text-align: center; margin: 0 0 6mm; text-indent: 0; }
            .author-photo img { width: 40mm; height: auto; }
            .colophon { padding-top: 120mm; text-align: center; font-size: 8.5pt; }
            .colophon p { text-align: center; text-indent: 0; }
            .toc li.toc-part { margin-top: 5mm; font-weight: bold; }
            .toc li.toc-section { margin: -1mm 0 2mm 6mm; font-size: .92em; }
            figure.illustration { margin: 6mm 0; text-align: center; page-break-inside: avoid; }
            figure.illustration img { max-width: 100%; max-height: 140mm; }
            figure.illustration figcaption { font-size: 9.5pt; font-style: italic; margin-top: 2mm; text-align: center; }
            figure.size-small { width: 40%; margin: 5mm auto; }
            figure.size-medium { width: 65%; margin: 5mm auto; }
            figure.size-small img, figure.size-medium img, figure.size-wide img { width: 100%; max-width: 100%; }
            figure.align-left { float: left; margin: 1mm 5mm 3mm 0; }
            figure.align-right { float: right; margin: 1mm 0 3mm 5mm; }
            .illustration-page { page-break-before: always; page-break-after: always; text-align: center; }
            .illustration-page img { display: block; margin: 0 auto; }
            p.illustration-caption { font-size: 9.5pt; font-style: italic; margin-top: 3mm; text-align: center; }
            @page bleed { margin: 0; }
            .bleed-page { page: bleed; page-break-before: always; page-break-after: always; }
            .bleed-page.spread-left { page-break-before: left; }
            .bleed-box { overflow: hidden; }
            .bleed-box img { display: block; }
            p.scene-break { text-align: center; margin: 4mm 0; letter-spacing: 2mm; }
            .chapter { counter-reset: footnote; }
            .footnote { float: footnote; font-size: 8.5pt; line-height: 1.3; text-align: left; text-indent: 0; font-style: normal; font-weight: normal; }
            ::footnote-call { content: counter(footnote); vertical-align: super; font-size: 70%; line-height: 0; }
            ::footnote-marker { content: counter(footnote) ". "; }
            @page main { @footnote { border-top: 0.5pt solid #999; padding-top: 1.5mm; margin-top: 4mm; } }
            blockquote { margin: 3mm 8mm; font-style: italic; }
            blockquote em { font-style: normal; }
            """;
}
