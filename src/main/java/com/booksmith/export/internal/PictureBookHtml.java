package com.booksmith.export.internal;

import com.booksmith.manuscript.api.BookLayout;
import com.booksmith.manuscript.api.PageDesign;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Locale;

import static com.booksmith.export.internal.BookHtml.escape;

/**
 * O PDF do livro ilustrado (infantil): cada capítulo é uma página do livro, sem margem, com a ilustração de fundo
 * cobrindo a página (com a sangria, se houver) e o texto numa caixa por cima, dentro da área segura. Sem sumário e
 * sem número de página, como nos livros ilustrados; a folha de rosto e os créditos/dedicatória vêm antes. A página
 * dupla ocupa as duas páginas do livro aberto e começa numa página da esquerda (par).
 *
 * <p>O fundo já vem no documento como a primeira ilustração ({@link #BACKGROUND_ID}, posta pela exportação, para o
 * ePub e o Word mostrarem a imagem); aqui ela sai do texto, porque vira o fundo.
 */
final class PictureBookHtml {

    /** Id do bloco da ilustração de fundo que a exportação põe no começo do documento de cada página. */
    static final String BACKGROUND_ID = "page-background";

    /** Distância do texto até o corte: a área segura das gráficas (o KDP pede ao menos 6,4 mm; aqui, folga). */
    static final double SAFE_MM = 12;

    private PictureBookHtml() {
    }

    /** O documento com a ilustração de fundo no começo (para ePub, Word e a conferência), ou o mesmo sem fundo. */
    static JsonNode withBackground(JsonNode document, PageDesign page) {
        if (page == null || page.imageId() == null || !(document instanceof ObjectNode doc)) {
            return document;
        }
        ObjectNode copy = doc.deepCopy();
        ArrayNode content = copy.has("content") && copy.get("content").isArray()
                ? (ArrayNode) copy.get("content") : copy.putArray("content");
        ObjectNode figure = content.insertObject(0);
        figure.put("type", "illustration");
        ObjectNode attrs = figure.putObject("attrs");
        attrs.put("id", BACKGROUND_ID);
        attrs.put("imageId", page.imageId());
        attrs.put("size", page.isSpread() ? "spread" : "bleed");
        return copy;
    }

    /** O documento sem a ilustração de fundo (o texto da página). */
    static JsonNode withoutBackground(JsonNode document) {
        if (!(document instanceof ObjectNode doc) || !doc.has("content")) {
            return document;
        }
        ObjectNode copy = doc.deepCopy();
        ArrayNode content = (ArrayNode) copy.get("content");
        for (int i = content.size() - 1; i >= 0; i--) {
            JsonNode attrs = content.get(i).get("attrs");
            if (attrs != null && attrs.has("id") && BACKGROUND_ID.equals(attrs.get("id").stringValue())) {
                content.remove(i);
            }
        }
        return copy;
    }

    static String html(ExportBook book) {
        BookLayout layout = book.layout();
        double[] page = PdfWriter.pageBoxMm(layout);
        StringBuilder pages = new StringBuilder();
        StringBuilder bookmarks = new StringBuilder();
        // A numeração das páginas, como o motor faz: a ímpar é a da direita (a sangria fica à direita), a par é a da
        // esquerda; a dupla começa numa par, com uma página em branco antes se precisar
        int last = 1 + frontPages(book);
        for (int i = 0; i < book.chapters().size(); i++) {
            ExportBook.Chapter chapter = book.chapters().get(i);
            PageDesign design = chapter.page() == null ? PageDesign.DEFAULT : chapter.page();
            String id = "page-" + (i + 1);
            bookmarks.append("<bookmark name=\"%s\" href=\"#%s\"/>\n".formatted(escape(chapter.title()), id));
            String text = BookHtml.body(withoutBackground(chapter.document()));
            boolean hasText = !"none".equals(design.text()) && text.contains("<p") && !text.replace("<p class=\"blank\">&#160;</p>", "").isBlank();
            if (design.isSpread()) {
                if ((last + 1) % 2 != 0) {
                    last++; // a página em branco que o motor põe antes da dupla
                }
                last += 2;
                // A esquerda e a direita do livro aberto, com a mesma imagem; o texto no lado escolhido
                pages.append(page(book, design, page, id, 0, "left".equals(design.textSide()) && hasText ? text : null, true, false));
                pages.append(page(book, design, page, null, page[0], "right".equals(design.textSide()) && hasText ? text : null, false, true));
            } else {
                last++;
                pages.append(page(book, design, page, id, 0, hasText ? text : null, false, last % 2 != 0));
            }
        }
        return """
                <!DOCTYPE html>
                <html lang="%1$s">
                <head>
                <meta charset="UTF-8"/>
                <title>%2$s</title>
                <style>%3$s</style>
                <bookmarks>
                %4$s</bookmarks>
                </head>
                <body>
                <div class="title-page" style="width: %6$smm; height: %7$smm; padding-top: %10$smm">
                <p class="book-title">%2$s</p>
                <p class="book-author">%5$s</p>
                </div>
                %8$s%9$s</body>
                </html>
                """.formatted(book.language(), escape(book.title()), css(page), bookmarks, escape(book.author()),
                mm(page[0]), mm(page[1] - 1), front(book, page), pages, mm(page[1] * 0.33));
    }

    private static int frontPages(ExportBook book) {
        int count = 0;
        for (String text : new String[] {book.pages().credits(), book.pages().dedication()}) {
            count += text == null || text.isBlank() ? 0 : 1;
        }
        return count;
    }

    /** Créditos e dedicatória, cada um na sua página, como nos livros ilustrados. */
    private static String front(ExportBook book, double[] page) {
        StringBuilder html = new StringBuilder();
        for (String text : new String[] {book.pages().credits(), book.pages().dedication()}) {
            if (text == null || text.isBlank()) {
                continue;
            }
            html.append("<div class=\"front-page\" style=\"width: %smm; height: %smm; padding-top: %smm\">"
                    .formatted(mm(page[0]), mm(page[1] - 1), mm(page[1] * 0.4)));
            text.lines().map(String::strip).filter(l -> !l.isEmpty())
                    .forEach(line -> html.append("<p>").append(escape(line)).append("</p>"));
            html.append("</div>\n");
        }
        return html.toString();
    }

    /**
     * Uma página: o fundo (cor e imagem cobrindo) e a caixa de texto. {@code offsetX}: na dupla, quanto da imagem já
     * ficou na página da esquerda. {@code right}: página da direita (ímpar), com a sangria à direita.
     */
    private static String page(ExportBook book, PageDesign design, double[] page, String id, double offsetX,
                               String text, boolean spreadLeft, boolean right) {
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"pb-page%s\"%s style=\"width: %smm; height: %smm; background-color: %s\">"
                .formatted(spreadLeft ? " spread-left" : "", id == null ? "" : " id=\"" + id + "\"", mm(page[0]),
                        mm(page[1] - 1), design.background()));
        String url = design.imageId() == null ? null : PdfWriter.dataUri(book, design.imageId());
        if (url != null) {
            int[] px = PrintCheck.pixels(book.illustrations().get(design.imageId()).bytes());
            if (px[0] > 0 && px[1] > 0) {
                Placement placement = design.isSpread() ? Placement.SPREAD : Placement.BLEED;
                Placement.Fit fit = placement.fit(px[0], px[1], book.layout(), false);
                html.append("<img class=\"pb-art\" src=\"%s\" alt=\"\" style=\"width: %smm; height: %smm; margin-left: -%smm; margin-top: -%smm\"/>"
                        .formatted(url, mm(fit.widthMm()), mm(fit.heightMm()), mm(fit.offsetXMm() + offsetX), mm(fit.offsetYMm())));
            }
        }
        if (text != null) {
            html.append(textBox(book.layout(), design, page, text, right));
        }
        html.append("</div>\n");
        return html.toString();
    }

    /**
     * A caixa de texto, posicionada em mm a partir do topo da página. A área segura conta do corte: a sangria fica na
     * borda de fora (à direita na página da direita, à esquerda na da esquerda) e em cima e embaixo.
     */
    private static String textBox(BookLayout layout, PageDesign design, double[] page, String text, boolean right) {
        double bleed = layout.hasBleed() ? BookLayout.BLEED_MM : 0;
        double inset = SAFE_MM + bleed;
        double left = SAFE_MM + (right ? 0 : bleed);
        double width = page[0] - 2 * SAFE_MM - bleed;
        String vertical = switch (design.text()) {
            case "top" -> "top: %smm".formatted(mm(inset));
            case "middle" -> "top: 50%; transform: translateY(-50%)";
            default -> "bottom: %smm".formatted(mm(inset + 1));
        };
        String band = design.hasBand()
                ? ("light".equals(design.color()) ? " band-dark" : " band-light") : "";
        return "<div class=\"pb-text %s%s align-%s\" style=\"left: %smm; width: %smm; %s; font-size: %spt\">%s</div>"
                .formatted(design.color(), band, design.align(), mm(left), mm(width), vertical,
                        mm(design.fontSizePt()), text);
    }

    private static String css(double[] page) {
        return """
                @page { size: %1$smm %2$smm; margin: 0; }
                body { margin: 0; font-family: Literata; color: #1a1a1a; }
                .title-page, .front-page { page-break-after: always; text-align: center; padding-left: 20mm;
                  padding-right: 20mm; box-sizing: border-box; }
                .book-title { font-size: 30pt; font-weight: bold; line-height: 1.15; margin: 0 0 10mm; }
                .book-author { font-size: 16pt; margin: 0; }
                .front-page p { font-size: 12pt; margin: 0 0 3mm; font-style: italic; }
                .pb-page { position: relative; overflow: hidden; page-break-before: always; }
                .pb-page.spread-left { page-break-before: left; }
                .pb-art { display: block; }
                .pb-text { position: absolute; line-height: 1.3; font-weight: bold; box-sizing: border-box; }
                .pb-text p { margin: 0 0 0.35em; text-indent: 0; }
                .pb-text p.blank { display: none; }
                .pb-text.dark { color: #1a1a1a; }
                .pb-text.light { color: #ffffff; }
                .pb-text.band-light { background-color: rgba(255, 255, 255, 0.82); padding: 4mm 5mm; border-radius: 4mm; }
                .pb-text.band-dark { background-color: rgba(0, 0, 0, 0.6); padding: 4mm 5mm; border-radius: 4mm; }
                .align-left { text-align: left; }
                .align-center { text-align: center; }
                .align-right { text-align: right; }
                """.formatted(mm(page[0]), mm(page[1]));
    }

    private static String mm(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
