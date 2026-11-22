package com.booksmith.export.internal;

import com.booksmith.manuscript.api.BookFont;
import com.booksmith.manuscript.api.BookLayout;
import com.booksmith.manuscript.api.ExtraPage;
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
        attrs.put("zoom", page.zoom());
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

    /**
     * O texto da página em blocos: a quebra de bloco ({@code sceneBreak}) separa um bloco do outro (no livro ilustrado
     * não há quebra de cena). Sem quebra, um bloco só.
     */
    static java.util.List<JsonNode> blocks(JsonNode document) {
        java.util.List<JsonNode> blocks = new java.util.ArrayList<>();
        if (!(document instanceof ObjectNode doc) || !(doc.get("content") instanceof ArrayNode content)) {
            blocks.add(document);
            return blocks;
        }
        ObjectNode current = emptyLike(doc);
        for (JsonNode node : content) {
            if ("sceneBreak".equals(node.path("type").asString(""))) {
                blocks.add(current);
                current = emptyLike(doc);
            } else {
                ((ArrayNode) current.get("content")).add(node.deepCopy());
            }
        }
        blocks.add(current);
        return blocks;
    }

    private static ObjectNode emptyLike(ObjectNode doc) {
        ObjectNode copy = doc.deepCopy();
        copy.putArray("content");
        return copy;
    }

    /** Um bloco de texto da página: o desenho dele e o HTML do texto. */
    record Placed(PageDesign design, String text) {
    }

    /**
     * Os blocos com texto, cada um com o seu desenho. Trecho a mais (mais quebras do que blocos desenhados) vai junto
     * no último bloco; bloco sem texto, ou o primeiro com "sem texto", não sai.
     */
    static java.util.List<Placed> placed(PageDesign design, JsonNode document) {
        java.util.List<JsonNode> parts = blocks(withoutBackground(document));
        String[] texts = new String[design.boxCount()];
        for (int i = 0; i < parts.size(); i++) {
            int slot = Math.min(i, texts.length - 1);
            String html = BookHtml.body(parts.get(i));
            texts[slot] = texts[slot] == null ? html : texts[slot] + html;
        }
        java.util.List<Placed> placed = new java.util.ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            PageDesign box = design.box(i);
            if (texts[i] != null && !"none".equals(box.text()) && hasText(texts[i])) {
                placed.add(new Placed(box, texts[i]));
            }
        }
        return placed;
    }

    private static boolean hasText(String html) {
        return html.contains("<p") && !html.replace("<p class=\"blank\">&#160;</p>", "").isBlank();
    }

    /** Na dupla, se o bloco fica na página da direita (no livre, o lado onde está o meio da caixa). */
    private static boolean onRight(PageDesign box) {
        return "free".equals(box.text()) ? box.x() + box.w() / 2 >= 50 : "right".equals(box.textSide());
    }

    /**
     * Uma página da história: o HTML dela e o lado no livro aberto ({@code left}/{@code right} na dupla; null na
     * página simples).
     */
    record Sheet(String html, String side, int number) {
    }

    /**
     * As páginas da história, na ordem: a dupla vira a página da esquerda e a da direita, com a mesma ilustração e
     * cada bloco de texto no lado dele. {@code src} dá o endereço de cada ilustração (no PDF, a memória; no ePub, o
     * arquivo); {@code before}: quantas páginas vêm antes (para o número de página e o lado da dupla).
     */
    static java.util.List<Sheet> sheets(ExportBook book, double[] page, java.util.function.Function<String, String> src,
                                        int before) {
        java.util.List<Sheet> sheets = new java.util.ArrayList<>();
        int last = before;
        for (int i = 0; i < book.chapters().size(); i++) {
            java.util.List<Sheet> chapter = chapterSheets(book, i, page, src, last);
            sheets.addAll(chapter);
            last = chapter.getLast().number();
        }
        return sheets;
    }

    /**
     * As folhas de uma página da história (a dupla são duas), depois da página {@code last}. A numeração é a do
     * motor: a ímpar é a da direita (a sangria fica à direita), a par é a da esquerda; a dupla começa numa par, com
     * uma página em branco antes se precisar.
     */
    private static java.util.List<Sheet> chapterSheets(ExportBook book, int i, double[] page,
                                                       java.util.function.Function<String, String> src, int last) {
        ExportBook.Chapter chapter = book.chapters().get(i);
        PageDesign design = chapter.page() == null ? PageDesign.DEFAULT : chapter.page();
        String id = "page-" + (i + 1);
        java.util.List<Placed> texts = placed(design, chapter.document());
        if (!design.isSpread()) {
            return java.util.List.of(new Sheet(page(book, design, page, id, 0, texts, false, (last + 1) % 2 != 0,
                    last + 1, src), null, last + 1));
        }
        int left = (last + 1) % 2 != 0 ? last + 2 : last + 1; // a página em branco que o motor põe antes da dupla
        return java.util.List.of(
                new Sheet(page(book, design, page, id, 0, texts.stream().filter(t -> !onRight(t.design())).toList(),
                        true, false, left, src), "left", left),
                new Sheet(page(book, design, page, null, page[0], texts.stream().filter(t -> onRight(t.design()))
                        .toList(), false, true, left + 1, src), "right", left + 1));
    }

    /**
     * Uma página do miolo, na ordem: uma folha da história ({@code chapter} = o índice da página da história,
     * {@code side} na dupla) ou uma página gerada ({@code key}, a de {@link ExtraPage#KEYS}; null na página em
     * branco).
     */
    record Part(String title, String html, String side, int chapter, String key) {

        boolean story() {
            return chapter >= 0;
        }
    }

    /** O miolo inteiro e quantas páginas tem impresso (com as em branco que o motor põe antes da dupla). */
    record Sequence(java.util.List<Part> parts, int pages) {
    }

    /**
     * O miolo na ordem do livro: as páginas que o Booksmith gera (se a diagramação pede) no lugar que o autor escolheu
     * entre as da história. {@code fill}: no impresso, completa com páginas em branco até um múltiplo de 4 (antes do
     * colofão, se ele fecha o livro) e abre o "sobre" com foto numa dupla.
     */
    static Sequence sequence(ExportBook book, double[] page, java.util.function.Function<String, String> src,
                             boolean fill) {
        java.util.List<String> ids = book.chapters().stream().map(ExportBook.Chapter::id)
                .filter(java.util.Objects::nonNull).map(id -> id.toString().toLowerCase(Locale.ROOT)).toList();
        java.util.List<ExtraPage> extras = book.layout().generatesPages() ? book.pages().arrangedExtras(ids)
                : java.util.List.of();
        java.util.List<Part> parts = new java.util.ArrayList<>();
        int[] last = {0};
        java.util.function.Consumer<String> extrasAt = place -> extras.stream()
                .filter(extra -> place.equalsIgnoreCase(extra.after()))
                .forEach(extra -> PictureBookExtras.render(book, extra, page, src, last[0], fill).forEach(x -> {
                    parts.add(new Part(x.title(), x.html(), null, -1, x.title().equals("Página em branco") ? null
                            : extra.key()));
                    last[0]++;
                }));
        extrasAt.accept(ExtraPage.START);
        for (int i = 0; i < book.chapters().size(); i++) {
            for (Sheet sheet : chapterSheets(book, i, page, src, last[0])) {
                parts.add(new Part(book.chapters().get(i).title() + ("right".equals(sheet.side()) ? " (continuação)" : ""),
                        sheet.html(), sheet.side(), i, null));
                last[0] = sheet.number();
            }
            if (book.chapters().get(i).id() != null) {
                extrasAt.accept(book.chapters().get(i).id().toString());
            }
        }
        extrasAt.accept(ExtraPage.END);
        if (fill && book.layout().generatesPages()) {
            int blanks = (4 - last[0] % 4) % 4;
            boolean colophonLast = !parts.isEmpty() && "colophon".equals(parts.getLast().key());
            for (int b = 0; b < blanks; b++) {
                var blank = PictureBookExtras.blankPage(page);
                parts.add(colophonLast ? parts.size() - 1 : parts.size(), new Part(blank.title(), blank.html(), null, -1, null));
            }
            last[0] += blanks;
        }
        return new Sequence(parts, last[0]);
    }

    static String html(ExportBook book) {
        return html(book, true);
    }

    /** Quantas páginas o livro ilustrado tem impresso (com as que o Booksmith gera e as em branco do fim). */
    static int pageCount(ExportBook book) {
        return sequence(book, PdfWriter.pageBoxMm(book.layout()), id -> "x", true).pages();
    }

    /** {@code fill}: completa o livro com páginas em branco até múltiplo de 4 (no impresso; no e-book não). */
    static String html(ExportBook book, boolean fill) {
        BookLayout layout = book.layout();
        double[] page = PdfWriter.pageBoxMm(layout);
        StringBuilder pages = new StringBuilder();
        StringBuilder bookmarks = new StringBuilder();
        for (int i = 0; i < book.chapters().size(); i++) {
            bookmarks.append("<bookmark name=\"%s\" href=\"#page-%d\"/>\n"
                    .formatted(escape(book.chapters().get(i).title()), i + 1));
        }
        java.util.function.Function<String, String> src = id -> "cover".equals(id)
                ? PdfWriter.IMAGE_SCHEME + ":cover" : PdfWriter.imageUri(book, id);
        // As páginas que o Booksmith gera, no lugar escolhido entre as da história, como num miolo de editora
        sequence(book, page, src, fill).parts().forEach(part -> pages.append(part.html()));
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
                %10$s%8$s%9$s</body>
                </html>
                """.formatted(book.language(), escape(book.title()), css(page, book.layout().bookFont()), bookmarks, escape(book.author()),
                mm(page[0]), mm(page[1] - 1), "", pages, "");
    }

    /**
     * Uma página: o fundo (cor e imagem cobrindo) e a caixa de texto. {@code offsetX}: na dupla, quanto da imagem já
     * ficou na página da esquerda. {@code right}: página da direita (ímpar), com a sangria à direita. {@code number}:
     * o número da página (sai no canto de fora, se a diagramação pedir).
     */
    private static String page(ExportBook book, PageDesign design, double[] page, String id, double offsetX,
                               java.util.List<Placed> texts, boolean spreadLeft, boolean right, int number,
                               java.util.function.Function<String, String> src) {
        StringBuilder html = new StringBuilder();
        // A caixa da página tem 1 mm a menos (na altura exata o motor abria uma página em branco depois); o fundo e a
        // arte ficam numa camada com a altura inteira da folha, para não sobrar uma faixa branca no pé
        html.append("<div class=\"pb-page%s\"%s style=\"width: %smm; height: %smm\">"
                .formatted(spreadLeft ? " spread-left" : "", id == null ? "" : " id=\"" + id + "\"", mm(page[0]),
                        mm(page[1] - 1)));
        html.append("<div class=\"pb-clip\" style=\"width: %smm; height: %smm; background-color: %s\">"
                .formatted(mm(page[0]), mm(page[1]), design.background()));
        String url = design.imageId() == null ? null : src.apply(design.imageId());
        double[] art = null;
        if (url != null) {
            int[] px = PrintCheck.pixels(book.illustrations().get(design.imageId()).bytes());
            if (px[0] > 0 && px[1] > 0) {
                Placement placement = design.isSpread() ? Placement.SPREAD : Placement.BLEED;
                Placement.Fit fit = placement.fit(px[0], px[1], book.layout(), false);
                // Cobrir a página (ou o livro aberto) e então o zoom do autor, com o centro onde ele arrastou
                double width = fit.widthMm() * design.zoom();
                double height = fit.heightMm() * design.zoom();
                double left = design.imageX() / 100 * fit.boxWidthMm() - width / 2 - offsetX;
                double top = design.imageY() / 100 * fit.boxHeightMm() - height / 2;
                html.append("<img class=\"pb-art\" src=\"%s\" alt=\"\" style=\"width: %smm; height: %smm; margin-left: %smm; margin-top: %smm\"/>"
                        .formatted(url, mm(width), mm(height), mm(left), mm(top)));
                art = new double[] {left, top, width, height};
            }
        }
        html.append("</div>");
        for (Placed placed : texts) {
            PageDesign box = placed.design();
            if (art != null && box.hasBlur()) {
                // O pedaço da arte atrás do texto, desfocado, entre a arte e o texto
                html.append(BlurPatch.html(book.illustrations().get(design.imageId()).bytes(), art,
                        textRect(book.layout(), box, page, placed.text(), right), new double[] {page[0], page[1] - 1},
                        box.fontSizePt(), !"light".equals(box.color()), design.background(), box.backdropHeight()));
            }
        }
        for (Placed placed : texts) {
            html.append(textBox(book.layout(), placed.design(), page, placed.text(), right));
        }
        if (book.layout().numbersPages()) {
            // Pequeno, no canto de fora, dentro da área segura (como nos livros ilustrados)
            double bleed = book.layout().hasBleed() ? BookLayout.BLEED_MM : 0;
            String side = right ? "right: %smm".formatted(mm(8)) : "left: %smm".formatted(mm(8 + bleed));
            html.append("<div class=\"pb-number %s\" style=\"%s; bottom: %smm\">%d</div>"
                    .formatted(design.color(), side, mm(6 + bleed), number));
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
        if ("free".equals(design.text())) {
            return freeTextBox(layout, design, text, right, bleed);
        }
        String vertical = switch (design.text()) {
            case "top" -> "top: %smm".formatted(mm(inset));
            case "middle" -> "top: 50%; transform: translateY(-50%)";
            default -> "bottom: %smm".formatted(mm(inset + 1));
        };
        String band = backdrop(design);
        return "<div class=\"pb-text %s%s align-%s\" style=\"left: %smm; width: %smm; %s; font-size: %spt;%s%s\">%s</div>"
                .formatted(design.color(), band, design.align(), mm(left), mm(width), vertical,
                        mm(design.fontSizePt()), fontStyle(layout, design), hazeHeight(design), text);
    }

    /**
     * O texto onde o autor arrastou: {@code x}, {@code y} e {@code w} em % do corte (na dupla, do livro aberto). Na
     * dupla, a caixa vai para a página onde está o meio dela e não passa da dobra.
     */
    private static String freeTextBox(BookLayout layout, PageDesign design, String text, boolean right, double bleed) {
        double trimW = layout.widthMm();
        double trimH = layout.heightMm();
        double canvas = design.isSpread() ? 2 * trimW : trimW;
        double boxW = Math.min(design.w() / 100 * canvas, trimW);
        double boxX = design.x() / 100 * canvas - (design.isSpread() && right ? trimW : 0);
        boxX = Math.max(0, Math.min(boxX, trimW - boxW));
        // Na página da esquerda, o corte começa depois da sangria (que fica à esquerda)
        double left = boxX + (right ? 0 : bleed);
        double top = bleed + design.y() / 100 * trimH;
        String band = backdrop(design);
        return "<div class=\"pb-text %s%s align-%s\" style=\"left: %smm; width: %smm; top: %smm; font-size: %spt;%s%s\">%s</div>"
                .formatted(design.color(), band, design.align(), mm(left), mm(boxW), mm(top), mm(design.fontSizePt()),
                        fontStyle(layout, design), hazeHeight(design), text);
    }

    /** Folga da névoa em cima e embaixo do texto, na altura 1 (a do CSS). */
    static final double HAZE_PAD_MM = 9;

    /**
     * Névoa mais alta: a folga em cima e embaixo cresce, e uma margem negativa do mesmo tanto mantém o texto onde
     * estava (em cima e no livre, a margem de cima; embaixo, a de baixo; no meio, a caixa cresce igual para os dois
     * lados e o texto fica no centro).
     */
    private static String hazeHeight(PageDesign design) {
        if (!design.hasHaze() || design.backdropHeight() == 1) {
            return "";
        }
        double pad = HAZE_PAD_MM * design.backdropHeight();
        double extra = pad - HAZE_PAD_MM;
        String margin = switch (design.text()) {
            case "middle" -> "";
            case "bottom" -> " margin-bottom: %smm;".formatted(mm(-extra));
            default -> " margin-top: %smm;".formatted(mm(-extra));
        };
        return " padding-top: %smm; padding-bottom: %smm;%s".formatted(mm(pad), mm(pad), margin);
    }

    /**
     * Onde fica a caixa do texto, em mm a partir do canto da página (esquerda, topo, largura, altura): a mesma conta
     * de {@link #textBox} e {@link #freeTextBox}, com a altura estimada pelo texto.
     */
    static double[] textRect(BookLayout layout, PageDesign design, double[] page, String text, boolean right) {
        double bleed = layout.hasBleed() ? BookLayout.BLEED_MM : 0;
        double height = page[1] - 1;
        double left;
        double width;
        double top;
        if ("free".equals(design.text())) {
            double trimW = layout.widthMm();
            double canvas = design.isSpread() ? 2 * trimW : trimW;
            width = Math.min(design.w() / 100 * canvas, trimW);
            double boxX = design.x() / 100 * canvas - (design.isSpread() && right ? trimW : 0);
            left = Math.max(0, Math.min(boxX, trimW - width)) + (right ? 0 : bleed);
            top = bleed + design.y() / 100 * layout.heightMm();
        } else {
            left = SAFE_MM + (right ? 0 : bleed);
            width = page[0] - 2 * SAFE_MM - bleed;
            top = 0;
        }
        double textHeight = BlurPatch.textHeightMm(text, width, design.fontSizePt());
        if (!"free".equals(design.text())) {
            double inset = SAFE_MM + bleed;
            top = switch (design.text()) {
                case "top" -> inset;
                case "middle" -> (height - textHeight) / 2;
                default -> height - (inset + 1) - textHeight;
            };
        }
        return new double[] {left, top, width, textHeight};
    }

    /** O destaque atrás do texto: faixa ou névoa, clara atrás do texto escuro e escura atrás do texto claro. */
    private static String backdrop(PageDesign design) {
        String tone = "light".equals(design.color()) ? "dark" : "light";
        if (design.hasBlur()) {
            return " blur";
        }
        if (design.hasHaze()) {
            return " haze-" + tone;
        }
        return design.hasBand() ? " band-" + tone : "";
    }

    /** A fonte da página, quando ela tem uma diferente da do livro (o nome vem do catálogo, nunca do usuário). */
    private static String fontStyle(BookLayout layout, PageDesign design) {
        BookFont font = design.fontOr(layout.bookFont());
        return font == layout.bookFont() ? "" : " font-family: '%s';".formatted(font.family());
    }

    static String css(double[] page, BookFont font) {
        return """
                @page { size: %1$smm %2$smm; margin: 0; }
                body { margin: 0; font-family: '%3$s'; color: #1a1a1a; }
                .title-page, .front-page { page-break-after: always; text-align: center; padding-left: 20mm;
                  padding-right: 20mm; box-sizing: border-box; }
                .book-title { font-size: 30pt; font-weight: bold; line-height: 1.15; margin: 0 0 10mm; }
                .book-author { font-size: 16pt; margin: 0; }
                .front-page p { font-size: 12pt; margin: 0 0 3mm; font-style: italic; }
                .pb-page { position: relative; page-break-before: always; }
                .pb-clip { position: absolute; left: 0; top: 0; overflow: hidden; }
                .pb-page.spread-left { page-break-before: left; }
                .pb-art { display: block; }
                .pb-blur { position: absolute; display: block; }
                .pb-number { position: absolute; font-size: 9pt; font-weight: bold; }
                .pb-number.dark { color: #1a1a1a; }
                .pb-number.light { color: #ffffff; }
                .pb-text { position: absolute; line-height: 1.3; font-weight: bold; box-sizing: border-box; }
                .pb-text p { margin: 0 0 0.35em; text-indent: 0; }
                .pb-text p.blank { display: none; }
                .pb-text.dark { color: #1a1a1a; }
                .pb-text.light { color: #ffffff; }
                .pb-text.band-light { background-color: rgba(255, 255, 255, 0.82); padding: 4mm 5mm; border-radius: 4mm; }
                .pb-text.band-dark { background-color: rgba(0, 0, 0, 0.6); padding: 4mm 5mm; border-radius: 4mm; }
                .pb-text.haze-light, .pb-text.haze-dark { padding: 9mm 12mm; background-size: 100%% 100%%;
                  background-repeat: no-repeat; }
                .pb-text.haze-light { background-image: url(%4$s); }
                .pb-text.haze-dark { background-image: url(%5$s); }
                .align-left { text-align: left; }
                .align-center { text-align: center; }
                .align-right { text-align: right; }
                """.formatted(mm(page[0]), mm(page[1]), font.family(), Haze.LIGHT, Haze.DARK) + PictureBookExtras.CSS;
    }

    private static String mm(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
