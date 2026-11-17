package com.booksmith.export.internal;

import com.booksmith.manuscript.api.BookLayout;
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
 * biografia na outra), as páginas em branco que fecham o livro em múltiplo de 4 e o colofão, na última. O fundo é o
 * que o autor escolheu na diagramação (uma ilustração da biblioteca ou uma cor).
 */
final class PictureBookExtras {

    /** Uma página gerada: o título (no sumário e na lista do que o livro tem) e o HTML. */
    record Extra(String title, String html) {
    }

    private static final double MARGIN_MM = 18;

    private PictureBookExtras() {
    }

    /** As páginas da frente, antes da história. {@code src}: o endereço de cada imagem ("cover" = a capa). */
    static List<Extra> front(ExportBook book, double[] page, Function<String, String> src) {
        List<Extra> pages = new ArrayList<>();
        BookPages p = book.pages();
        String coverUrl = book.cover() != null && PdfWriter.embeds(book.cover().contentType()) ? src.apply("cover") : null;
        if (coverUrl != null) {
            pages.add(new Extra("Folha de rosto", sheet(book, page, src, art(coverUrl, book.cover().bytes(), page), "")));
        } else {
            pages.add(new Extra("Folha de rosto", sheet(book, page, src, null,
                    block(page, 0.30, "rosto", "<p class=\"x-title\">%s</p><p class=\"x-author\">%s</p>"
                            .formatted(escape(book.title()), escape(book.author()))))));
        }
        pages.add(new Extra("Créditos", sheet(book, page, src, null, block(page, 0.07, "creditos", credits(book)))));
        pages.add(new Extra("Página de rosto", sheet(book, page, src, null, block(page, 0.36, "rosto",
                "<p class=\"x-title\">%s</p><p class=\"x-author\">%s</p>".formatted(escape(book.title()),
                        escape(book.author()))))));
        if (present(p.dedication())) {
            pages.add(new Extra("Dedicatória", sheet(book, page, src, null,
                    block(page, 0.18, "texto", "<h2>Dedicatória</h2>" + paragraphs(p.dedication())))));
        }
        if (present(p.epigraph())) {
            String source = present(p.epigraphSource()) ? "<p class=\"x-source\">%s</p>".formatted(escape(p.epigraphSource())) : "";
            pages.add(new Extra("Epígrafe", sheet(book, page, src, null,
                    block(page, 0.4, "epigrafe", paragraphs(p.epigraph()) + source))));
        }
        if (present(p.acknowledgments())) {
            pages.add(new Extra("Agradecimentos", sheet(book, page, src, null,
                    block(page, 0.18, "texto", "<h2>Agradecimentos</h2>" + paragraphs(p.acknowledgments())))));
        }
        return pages;
    }

    /**
     * As páginas do fim, depois da história, que termina na página {@code lastStoryPage}. Com {@code fill}, completa
     * o livro com páginas em branco até um múltiplo de 4 (o colofão fica na última).
     */
    static List<Extra> back(ExportBook book, double[] page, Function<String, String> src, int lastStoryPage, boolean fill) {
        List<Extra> pages = new ArrayList<>();
        BookPages p = book.pages();
        int last = lastStoryPage;
        if (present(p.aboutAuthor())) {
            String title = present(p.aboutAuthorTitle()) ? p.aboutAuthorTitle() : "Sobre o autor";
            String photo = p.authorPhotoId() == null ? null : PdfWriter.imageUri(book, p.authorPhotoId().toString()) == null
                    ? null : src.apply(p.authorPhotoId().toString());
            if (photo != null) {
                // A foto e o nome na página da esquerda, a biografia na da direita (o livro aberto)
                if (fill && (last + 1) % 2 != 0) {
                    pages.add(new Extra("Página em branco", blank(page)));
                    last++;
                }
                pages.add(new Extra(title, sheet(book, page, src, null, block(page, 0.28, "autor",
                        "<div class=\"x-photo\" style=\"background-image: url(%s)\"></div><p class=\"x-name\">%s</p>"
                                .formatted(photo, escape(book.author()))))));
                pages.add(new Extra(title + " (continuação)", sheet(book, page, src, null,
                        block(page, 0.2, "texto", paragraphs(p.aboutAuthor())))));
                last += 2;
            } else {
                pages.add(new Extra(title, sheet(book, page, src, null,
                        block(page, 0.18, "texto", "<h2>%s</h2>".formatted(escape(title)) + paragraphs(p.aboutAuthor())))));
                last++;
            }
        }
        boolean colophon = present(p.colophon());
        if (fill) {
            int total = last + (colophon ? 1 : 0);
            for (int i = 0; i < (4 - total % 4) % 4; i++) {
                pages.add(new Extra("Página em branco", blank(page)));
            }
        }
        if (colophon) {
            pages.add(new Extra("Colofão", sheet(book, page, src, null, block(page, 0.62, "colofao", paragraphs(p.colophon())))));
        }
        return pages;
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

    /** Uma página gerada: o fundo (a ilustração escolhida ou a cor), uma arte por cima (a capa) e o texto. */
    private static String sheet(ExportBook book, double[] page, Function<String, String> src, String art, String body) {
        BookLayout layout = book.layout();
        String background = "";
        if (art == null && layout.pagesImageId() != null && book.illustrations().containsKey(layout.pagesImageId())
                && PdfWriter.imageUri(book, layout.pagesImageId()) != null) {
            background = art(src.apply(layout.pagesImageId()), book.illustrations().get(layout.pagesImageId()).bytes(), page);
        }
        return "<div class=\"pb-page x-page\" style=\"width: %smm; height: %smm\"><div class=\"pb-clip\" style=\"width: %smm; height: %smm; background-color: %s\">%s%s</div>%s</div>\n"
                .formatted(mm(page[0]), mm(page[1] - 1), mm(page[0]), mm(page[1]), layout.pagesColor(), background,
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
