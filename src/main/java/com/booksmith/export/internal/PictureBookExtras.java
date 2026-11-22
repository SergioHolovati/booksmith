package com.booksmith.export.internal;

import com.booksmith.manuscript.api.BookLayout;
import com.booksmith.manuscript.api.ExtraPage;
import com.booksmith.manuscript.api.Manuscripts.BookPages;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static com.booksmith.export.internal.BookHtml.escape;

/**
 * As páginas que o Booksmith gera no livro ilustrado, na ordem de um miolo de editora: na frente, a folha de rosto
 * ilustrada (a capa), os créditos (copyright, ficha técnica, ficha catalográfica, ISBN), a página de rosto (título e
 * autor), a dedicatória, a epígrafe e os agradecimentos; no fim, o "sobre o autor" (a foto e o nome numa página, a
 * biografia na outra) e o colofão. O autor pode mudá-las de lugar (entre as páginas da história) e dar a cada uma um
 * fundo próprio; sem isso, o fundo é o da diagramação (uma ilustração da biblioteca ou uma cor).
 */
final class PictureBookExtras {

    /** Uma página gerada: o título (no sumário e na lista do que o livro tem) e o HTML. */
    record Extra(String title, String html) {
    }

    private static final double MARGIN_MM = 18;

    private PictureBookExtras() {
    }

    /**
     * As páginas de uma página gerada (o "sobre" com foto são duas: a foto e o nome à esquerda, a biografia à
     * direita). {@code last}: o número da página que vem antes; com {@code fill} (o impresso), põe uma página em
     * branco antes do "sobre" com foto se precisar, para ele abrir o livro numa dupla.
     */
    static List<Extra> render(ExportBook book, ExtraPage extra, double[] page, Function<String, String> src, int last,
                              boolean fill) {
        BookPages p = book.pages();
        List<Extra> pages = new ArrayList<>();
        String rosto = "<p class=\"x-title\">%s</p><p class=\"x-author\">%s</p>".formatted(escape(book.title()),
                escape(book.author()));
        switch (extra.key()) {
            case "cover" -> {
                String coverUrl = book.cover() != null && PdfWriter.embeds(book.cover().contentType())
                        ? src.apply("cover") : null;
                pages.add(new Extra("Folha de rosto", coverUrl != null
                        ? sheet(book, extra, page, src, art(coverUrl, book.cover().bytes(), page), "")
                        : sheet(book, extra, page, src, null, block(page, 0.30, "rosto", rosto))));
            }
            case "credits" -> pages.add(new Extra("Créditos",
                    sheet(book, extra, page, src, null, block(page, 0.07, "creditos", credits(book)))));
            case "title" -> pages.add(new Extra("Página de rosto",
                    sheet(book, extra, page, src, null, block(page, 0.36, "rosto", rosto))));
            case "dedication" -> text(book, extra, page, src, pages, "Dedicatória", p.dedication(), 0.18, "texto",
                    "<h2>Dedicatória</h2>");
            case "epigraph" -> {
                String source = present(p.epigraphSource())
                        ? "<p class=\"x-source\">%s</p>".formatted(escape(p.epigraphSource())) : "";
                if (present(p.epigraph())) {
                    pages.add(new Extra("Epígrafe", sheet(book, extra, page, src, null,
                            block(page, 0.4, "epigrafe", paragraphs(p.epigraph()) + source))));
                }
            }
            case "acknowledgments" -> text(book, extra, page, src, pages, "Agradecimentos", p.acknowledgments(), 0.18,
                    "texto", "<h2>Agradecimentos</h2>");
            case "about" -> {
                if (present(p.aboutAuthor())) {
                    about(book, extra, page, src, last, fill, pages);
                }
            }
            case "colophon" -> text(book, extra, page, src, pages, "Colofão", p.colophon(), 0.62, "colofao", "");
            default -> {
                // página desconhecida: não sai
            }
        }
        return pages;
    }

    /** Uma página de texto (dedicatória, agradecimentos, colofão), se tiver texto. */
    private static void text(ExportBook book, ExtraPage extra, double[] page, Function<String, String> src,
                             List<Extra> pages, String title, String text, double top, String kind, String heading) {
        if (present(text)) {
            pages.add(new Extra(title, sheet(book, extra, page, src, null, block(page, top, kind, heading + paragraphs(text)))));
        }
    }

    private static void about(ExportBook book, ExtraPage extra, double[] page, Function<String, String> src, int last,
                              boolean fill, List<Extra> pages) {
        BookPages p = book.pages();
        String title = present(p.aboutAuthorTitle()) ? p.aboutAuthorTitle() : "Sobre o autor";
        String photo = p.authorPhotoId() == null || PdfWriter.imageUri(book, p.authorPhotoId().toString()) == null
                ? null : src.apply(p.authorPhotoId().toString());
        if (photo == null) {
            pages.add(new Extra(title, sheet(book, extra, page, src, null,
                    block(page, 0.18, "texto", "<h2>%s</h2>".formatted(escape(title)) + paragraphs(p.aboutAuthor())))));
            return;
        }
        // A foto e o nome na página da esquerda (par), a biografia na da direita (o livro aberto)
        if (fill && (last + 1) % 2 != 0) {
            pages.add(blankPage(page));
        }
        pages.add(new Extra(title, sheet(book, extra, page, src, null, block(page, 0.28, "autor",
                "<div class=\"x-photo\" style=\"background-image: url(%s)\"></div><p class=\"x-name\">%s</p>"
                        .formatted(photo, escape(book.author()))))));
        pages.add(new Extra(title + " (continuação)", sheet(book, extra, page, src, null,
                block(page, 0.2, "texto", paragraphs(p.aboutAuthor())))));
    }

    /** Uma página em branco (as que fecham o livro em múltiplo de 4). */
    static Extra blankPage(double[] page) {
        return new Extra("Página em branco", blank(page));
    }

    /** Copyright, os créditos do autor (ficha técnica), a ficha catalográfica num quadro e o ISBN do impresso. */
    private static String credits(ExportBook book) {
        BookPages p = book.pages();
        StringBuilder html = new StringBuilder();
        String credits = p.credits() == null ? "" : p.credits();
        if (!credits.contains("©") && !credits.toLowerCase(java.util.Locale.ROOT).contains("copyright")) {
            int year = book.generatedAt().atZone(ZoneId.of("America/Sao_Paulo")).getYear();
            html.append("<p><strong>Copyright © %d por %s.</strong></p><p>Todos os direitos reservados.</p>"
                    .formatted(year, escape(book.author())));
        }
        html.append(paragraphs(credits));
        if (present(p.catalogCard())) {
            html.append("<div class=\"x-card\">").append(paragraphs(p.catalogCard())).append("</div>");
        }
        if (present(p.isbnPrint()) && !credits.contains(p.isbnPrint())) {
            html.append("<p>ISBN %s</p>".formatted(escape(p.isbnPrint())));
        }
        return html.toString();
    }

    /**
     * Uma página gerada: o fundo, uma arte por cima (a capa) e o texto. O fundo é o que o autor escolheu só para esta
     * página (a ilustração ou a cor); sem isso, o das páginas geradas na diagramação.
     */
    private static String sheet(ExportBook book, ExtraPage extra, double[] page, Function<String, String> src,
                                String art, String body) {
        BookLayout layout = book.layout();
        String imageId = extra.imageId() != null ? extra.imageId() : extra.color() != null ? null : layout.pagesImageId();
        String color = extra.color() != null ? extra.color() : layout.pagesColor();
        String background = "";
        if (art == null && imageId != null && book.illustrations().containsKey(imageId)
                && PdfWriter.imageUri(book, imageId) != null) {
            background = art(src.apply(imageId), book.illustrations().get(imageId).bytes(), page);
        }
        return "<div class=\"pb-page x-page\" style=\"width: %smm; height: %smm\"><div class=\"pb-clip\" style=\"width: %smm; height: %smm; background-color: %s\">%s%s</div>%s</div>\n"
                .formatted(mm(page[0]), mm(page[1] - 1), mm(page[0]), mm(page[1]), color, background,
                        art == null ? "" : art, body);
    }

    /** A imagem cobrindo a página inteira, centralizada (como a ilustração de fundo das páginas da história). */
    private static String art(String url, byte[] bytes, double[] page) {
        int[] px = PrintCheck.pixels(bytes);
        if (px[0] <= 0 || px[1] <= 0) {
            return "";
        }
        double scale = Math.max(page[0] / px[0], page[1] / px[1]);
        double w = px[0] * scale;
        double h = px[1] * scale;
        return "<img class=\"pb-art\" src=\"%s\" alt=\"\" style=\"width: %smm; height: %smm; margin-left: %smm; margin-top: %smm\"/>"
                .formatted(url, mm(w), mm(h), mm((page[0] - w) / 2), mm((page[1] - h) / 2));
    }

    /** O bloco de texto da página, com margem dos lados e o topo em fração da altura. */
    private static String block(double[] page, double top, String kind, String html) {
        return "<div class=\"x-body x-%s\" style=\"left: %smm; width: %smm; top: %smm\">%s</div>"
                .formatted(kind, mm(MARGIN_MM), mm(page[0] - 2 * MARGIN_MM), mm(page[1] * top), html);
    }

    private static String blank(double[] page) {
        return "<div class=\"pb-page x-blank\" style=\"width: %smm; height: %smm\"></div>\n".formatted(mm(page[0]),
                mm(page[1] - 1));
    }

    private static String paragraphs(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder html = new StringBuilder();
        text.lines().map(String::strip).filter(line -> !line.isEmpty())
                .forEach(line -> html.append("<p>").append(escape(line)).append("</p>"));
        return html.toString();
    }

    private static boolean present(String text) {
        return text != null && !text.isBlank();
    }

    /** O CSS das páginas geradas. */
    static final String CSS = """
            .x-body { position: absolute; box-sizing: border-box; color: #1a1a1a; }
            .x-body p { margin: 0 0 2.2mm; }
            .x-body h2 { font-size: 15pt; margin: 0 0 6mm; text-align: center; }
            .x-rosto { text-align: center; }
            .x-title { font-size: 30pt; font-weight: bold; line-height: 1.15; margin: 0 0 8mm; }
            .x-author { font-size: 15pt; }
            .x-creditos { font-size: 8.5pt; line-height: 1.35; text-align: center; }
            .x-card { border: 0.3mm solid #555555; padding: 3mm 4mm; margin: 4mm auto; width: 80%; text-align: left; font-size: 7.5pt; }
            .x-texto { font-size: 11.5pt; line-height: 1.5; text-align: justify; }
            .x-epigrafe { font-size: 12pt; font-style: italic; text-align: center; }
            .x-source { font-style: normal; font-size: 10pt; }
            .x-autor { text-align: center; }
            .x-photo { width: 60mm; height: 60mm; margin: 0 auto 6mm; border-radius: 30mm; background-size: cover;
              background-position: center; }
            .x-name { font-size: 16pt; font-weight: bold; }
            .x-colofao { font-size: 9pt; text-align: center; }
            """;

    private static String mm(double value) {
        return String.valueOf(Math.round(value * 100) / 100.0);
    }
}
