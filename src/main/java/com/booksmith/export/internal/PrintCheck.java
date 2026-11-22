package com.booksmith.export.internal;

import com.booksmith.manuscript.api.BookLayout;
import com.booksmith.media.api.ImageStorage.ImageData;
import tools.jackson.databind.JsonNode;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Conferência das ilustrações para impressão (§4.4 do produto): em que tamanho cada uma sai na página do PDF
 * diagramado e quantos pontos por polegada (DPI) isso dá. Gráfica pede 300 DPI; abaixo disso a imagem sai borrada.
 * Calculado na hora, porque depende da diagramação (tamanho da página e margens), não da imagem.
 *
 * <p>No PDF, a imagem sai no tamanho natural (1 pixel = 1/96 de polegada, como no navegador), limitada à largura
 * do texto e a {@value PdfWriter#ILLUSTRATION_MAX_HEIGHT_MM} mm de altura, sem distorcer.
 */
public final class PrintCheck {

    /** O que as gráficas pedem para imagem impressa. */
    static final int MIN_DPI = 300;

    private static final double MM_PER_INCH = 25.4;

    private PrintCheck() {
    }

    /**
     * Uma ilustração no texto.
     *
     * @param chapter  título do capítulo onde ela está
     * @param caption  legenda (vazia = sem legenda)
     * @param widthPx  largura da imagem em pixels (0 = não deu para ler)
     * @param widthMm  largura na página do PDF, em mm
     * @param dpi      pontos por polegada no tamanho impresso
     * @param printable entra no PDF (WebP não entra: sai só a legenda)
     * @param minWidthPx largura mínima, em pixels, para 300 DPI no mesmo tamanho na página
     * @param ok         sai nítida na impressão: entra no PDF e tem 300 DPI ou mais
     */
    public record Illustration(String imageId, String chapter, String caption, int widthPx, int heightPx, double widthMm,
                        double heightMm, int dpi, boolean printable, int minWidthPx, boolean ok, String size) {

        Illustration(String imageId, String chapter, String caption, int widthPx, int heightPx, double widthMm,
                     double heightMm, int dpi, boolean printable, int minWidthPx, String size) {
            this(imageId, chapter, caption, widthPx, heightPx, widthMm, heightMm, dpi, printable, minWidthPx,
                    printable && dpi >= MIN_DPI, size);
        }
    }

    /**
     * {@code textWidthMm}: largura da área de texto na diagramação, o máximo de uma ilustração. {@code needsBleed}:
     * há ilustração até a borda (ou dupla) e a diagramação está sem sangria: na gráfica, ela sairia com filete branco.
     */
    /**
     * @param pages     livro ilustrado: a conferência das páginas para a gráfica (o que o miolo tem e o que falta)
     * @param pageCount livro ilustrado: quantas páginas o miolo impresso tem
     */
    public record Result(int minDpi, double textWidthMm, List<Illustration> illustrations, boolean needsBleed,
                         List<PageCheck> pages, int pageCount) {

        /**
         * Com o aviso dos campos do modelo que ficaram por completar ("[nome de quem revisou]"): essas linhas não
         * saem no livro.
         */
        Result withPending(com.booksmith.manuscript.api.Manuscripts.BookPages raw) {
            List<String> pending = new ArrayList<>();
            for (String[] field : new String[][] {{"Créditos", raw.credits()}, {"Ficha catalográfica", raw.catalogCard()},
                    {"Dedicatória", raw.dedication()}, {"Agradecimentos", raw.acknowledgments()},
                    {"Sobre o autor", raw.aboutAuthor()}, {"Colofão", raw.colophon()}}) {
                var lines = com.booksmith.manuscript.api.Manuscripts.BookPages.pending(field[1]);
                if (!lines.isEmpty()) {
                    pending.add(field[0] + " (" + lines.size() + (lines.size() == 1 ? " linha)" : " linhas)"));
                }
            }
            List<PageCheck> checks = new ArrayList<>(pages);
            checks.add(0, new PageCheck("template", "Campos do modelo", pending.isEmpty() ? "ok" : "warn",
                    pending.isEmpty() ? "Tudo completado."
                            : "Falta completar o que está entre colchetes em: " + String.join(", ", pending)
                            + ". Até lá, essas linhas não saem no livro."));
            return new Result(minDpi, textWidthMm, illustrations, needsBleed, List.copyOf(checks), pageCount);
        }
    }

    /**
     * Um item da conferência para a gráfica.
     *
     * @param status {@code ok} (pronto), {@code warn} (a gráfica ou o registro pedem e falta) ou {@code info}
     *               (opcional, ou como o livro vai sair)
     */
    public record PageCheck(String key, String label, String status, String detail) {
    }

    static Result check(ExportBook book) {
        BookLayout layout = book.layout();
        double maxWidth = PdfWriter.textWidthMm(layout);
        List<Illustration> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ExportBook.Chapter chapter : book.chapters()) {
            List<JsonNode> nodes = new ArrayList<>();
            collect(chapter.document(), nodes);
            for (JsonNode attrs : nodes) {
                String imageId = attrs.get("imageId").stringValue();
                if (!seen.add(chapter.title() + "|" + imageId + "|" + Placement.of(attrs))) {
                    continue;
                }
                JsonNode captionNode = attrs.get("caption");
                String caption = captionNode == null || captionNode.isNull() ? "" : captionNode.stringValue().strip();
                ImageData image = book.illustrations().get(imageId);
                if (image != null) {
                    Illustration measured = measure(imageId, chapter.title(), caption, image, Placement.of(attrs), layout);
                    // No livro ilustrado, o fundo com zoom: a mesma imagem esticada mais, menos DPI
                    JsonNode zoom = attrs.get("zoom");
                    found.add(zoom != null && zoom.isNumber() && zoom.doubleValue() > 0 ? zoomed(measured, zoom.doubleValue()) : measured);
                }
            }
        }
        boolean edge = found.stream().anyMatch(i -> "bleed".equals(i.size()) || "spread".equals(i.size()));
        boolean bleedMissing = edge && !layout.hasBleed();
        if (!book.pictureBook()) {
            return new Result(MIN_DPI, round(maxWidth), List.copyOf(found), bleedMissing, List.of(), 0);
        }
        int pageCount = PictureBookHtml.pageCount(book);
        return new Result(MIN_DPI, round(maxWidth), List.copyOf(found), bleedMissing,
                pictureBookPages(book, found, pageCount), pageCount);
    }

    /** O que o miolo do livro ilustrado precisa ter para a gráfica (no modelo das editoras). */
    static List<PageCheck> pictureBookPages(ExportBook book, List<Illustration> illustrations, int pageCount) {
        var p = book.pages();
        BookLayout layout = book.layout();
        List<PageCheck> checks = new ArrayList<>();
        if (!layout.generatesPages()) {
            checks.add(new PageCheck("autoPages", "Páginas da frente e do fim", "info",
                    "Você monta todas as páginas: confira se o livro tem folha de rosto, créditos e página de rosto."));
        } else {
            checks.add(new PageCheck("cover", "Folha de rosto ilustrada", book.cover() != null ? "ok" : "info",
                    book.cover() != null ? "A capa do livro abre o miolo." : "Sem capa: sai o título e o autor sobre o fundo."));
            checks.add(new PageCheck("credits", "Créditos (ficha técnica)", present(p.credits()) ? "ok" : "warn",
                    present(p.credits()) ? "Com o copyright."
                            : "Só o copyright: falta a ficha técnica (edição, revisão, ilustração, diagramação, editora)."));
            checks.add(new PageCheck("catalogCard", "Ficha catalográfica", present(p.catalogCard()) ? "ok" : "warn",
                    present(p.catalogCard()) ? "No quadro, nos créditos."
                            : "Peça a um bibliotecário (CRB) ou à editora; vai nos créditos."));
            checks.add(new PageCheck("isbn", "ISBN do impresso", present(p.isbnPrint()) ? "ok" : "warn",
                    present(p.isbnPrint()) ? "Nos créditos." : "Sem ISBN: informe no guia de publicação (Publicar)."));
            checks.add(new PageCheck("titlePage", "Página de rosto", "ok", "Título e autor."));
            checks.add(new PageCheck("dedication", "Dedicatória", present(p.dedication()) ? "ok" : "info",
                    present(p.dedication()) ? "Na página dela." : "Opcional."));
            checks.add(new PageCheck("acknowledgments", "Agradecimentos", present(p.acknowledgments()) ? "ok" : "info",
                    present(p.acknowledgments()) ? "Na página deles." : "Opcional."));
            boolean about = present(p.aboutAuthor());
            checks.add(new PageCheck("aboutAuthor", "Sobre o autor", about && p.authorPhotoId() != null ? "ok" : "warn",
                    !about ? "Falta o texto sobre quem escreveu (com foto, fica na página dupla do fim)."
                            : p.authorPhotoId() == null ? "Falta a foto." : "Foto e nome de um lado, a biografia do outro."));
            checks.add(new PageCheck("colophon", "Colofão (última página)", present(p.colophon()) ? "ok" : "info",
                    present(p.colophon()) ? "Na última página." : "Opcional: fontes, papel, gráfica e ano."));
        }
        checks.add(new PageCheck("pageCount", "Total de páginas", pageCount % 4 == 0 ? "ok" : "warn",
                pageCount % 4 == 0 ? pageCount + " páginas (múltiplo de 4)."
                        : pageCount + " páginas: gráficas imprimem em múltiplos de 4; faltam " + (4 - pageCount % 4) + "."));
        checks.add(new PageCheck("bleed", "Sangria", layout.hasBleed() ? "ok" : "warn",
                layout.hasBleed() ? "3,2 mm nas bordas de fora."
                        : "Sem sangria: a ilustração até a borda pode sair com filete branco no corte."));
        long low = illustrations.stream().filter(i -> !i.ok()).count();
        checks.add(new PageCheck("resolution", "Resolução das ilustrações", low == 0 ? "ok" : "warn",
                low == 0 ? "Todas com " + MIN_DPI + " DPI ou mais."
                        : low + (low == 1 ? " ilustração sai" : " ilustrações saem") + " com menos de " + MIN_DPI + " DPI."));
        checks.add(new PageCheck("spreads", "Formato do PDF", "info", layout.joinsSpreads()
                ? "Páginas lado a lado (o livro aberto), a primeira e a última sozinhas."
                : "Uma página por folha."));
        return checks;
    }

    private static boolean present(String text) {
        return text != null && !text.isBlank();
    }

    /**
     * Tamanho na página conforme o lugar escolhido ({@link Placement#fit}): sem lugar, o natural (96 px por
     * polegada) limitado à largura do texto e à altura máxima; até a borda e dupla, o que cobre a página (o DPI conta
     * a imagem inteira, mesmo a parte cortada).
     */
    static Illustration measure(String imageId, String chapter, String caption, ImageData image, Placement placement,
                                BookLayout layout) {
        int[] size = pixels(image.bytes());
        boolean printable = PdfWriter.embeds(image.contentType());
        String where = placement.css();
        if (size[0] == 0 || size[1] == 0) {
            return new Illustration(imageId, chapter, caption, 0, 0, 0, 0, 0, printable, 0, where);
        }
        Placement.Fit fit = placement.fit(size[0], size[1], layout, !caption.isEmpty());
        double widthMm = fit.widthMm();
        double heightMm = fit.heightMm();
        // + 0,001: a imagem no tamanho natural dá 96 exatos, não 95,9999
        int dpi = (int) Math.floor(size[0] / (widthMm / MM_PER_INCH) + 0.001);
        int minWidthPx = (int) Math.ceil(widthMm / MM_PER_INCH * MIN_DPI);
        return new Illustration(imageId, chapter, caption, size[0], size[1], round(widthMm), round(heightMm), dpi,
                printable, minWidthPx, where);
    }

    /** A medida com o zoom do autor: o tamanho na página multiplicado, o DPI dividido. */
    static Illustration zoomed(Illustration i, double zoom) {
        int dpi = (int) Math.floor(i.dpi() / zoom + 0.001);
        return new Illustration(i.imageId(), i.chapter(), i.caption(), i.widthPx(), i.heightPx(), round(i.widthMm() * zoom),
                round(i.heightMm() * zoom), dpi, i.printable(), (int) Math.ceil(i.minWidthPx() * zoom), i.size());
    }

    /** Largura e altura em pixels, lendo só o cabeçalho; {0, 0} se o formato não é lido (ex.: WebP). */
    static int[] pixels(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = input == null ? java.util.Collections.emptyIterator()
                    : ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return new int[] {0, 0};
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                return new int[] {reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            return new int[] {0, 0};
        }
    }

    private static void collect(JsonNode node, List<JsonNode> found) {
        JsonNode attrs = node.get("attrs");
        if ("illustration".equals(BookHtml.type(node))
                && attrs != null && attrs.get("imageId") != null && !attrs.get("imageId").isNull()) {
            found.add(attrs);
        }
        JsonNode content = node.get("content");
        if (content != null) {
            content.forEach(child -> collect(child, found));
        }
    }

    private static double round(double mm) {
        return Math.round(mm * 10) / 10.0;
    }
}
