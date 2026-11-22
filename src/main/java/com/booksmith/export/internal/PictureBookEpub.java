package com.booksmith.export.internal;

import com.booksmith.manuscript.api.BookFont;
import com.booksmith.manuscript.api.BookLayout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipOutputStream;

import static com.booksmith.export.internal.BookHtml.escape;

/**
 * O e-book do livro ilustrado: ePub 3 de layout fixo (pré-paginado), o formato dos livros infantis no Kindle, no
 * Apple Books e no Google Play. Cada página tem o tamanho do livro, com a ilustração no fundo e o texto por cima, no
 * lugar que o autor escolheu (o mesmo desenho do PDF, sem a sangria e sem o número de página); a dupla vira a página
 * da esquerda e a da direita, que o leitor mostra lado a lado.
 *
 * <p>As medidas do desenho são em mm: com a página do tamanho do livro em px CSS (96 por polegada), elas valem igual
 * no leitor de ePub.
 */
final class PictureBookEpub {

    private static final DateTimeFormatter MODIFIED = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(java.time.ZoneOffset.UTC);
    private static final double PX_PER_MM = 96 / 25.4;

    private PictureBookEpub() {
    }

    /** Uma página do ePub: o arquivo, o título (no sumário), o corpo e o lado no livro aberto (null: o leitor decide). */
    record Page(String file, String title, String body, String side) {
    }

    static byte[] write(ExportBook book) {
        BookLayout print = book.layout();
        // O e-book não tem sangria nem número de página
        BookLayout layout = new BookLayout(print.trim(), print.fontSize(), print.leading(), print.margins(),
                print.chapterStyle(), print.sceneBreak(), print.customWidthMm(), print.customHeightMm(), false,
                print.autoPages(), false, print.fixedLayout(), print.font(), print.pagesImageId(), print.pagesColor(), false);
        ExportBook ebook = book.withLayout(layout);
        double[] size = PdfWriter.pageBoxMm(layout);
        List<Page> pages = pages(ebook, size);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            EpubWriter.stored(zip, "mimetype", "application/epub+zip".getBytes(StandardCharsets.US_ASCII));
            EpubWriter.text(zip, "META-INF/container.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                    """);
            java.util.Set<BookFont> fonts = PdfWriter.fontsOf(ebook);
            EpubWriter.text(zip, "EPUB/package.opf", packageDocument(ebook, pages, fonts));
            EpubWriter.text(zip, "EPUB/nav.xhtml", nav(ebook, pages));
            EpubWriter.text(zip, "EPUB/css/book.css", css(size, ebook, fonts));
            for (BookFont font : fonts) {
                for (String style : EpubWriter.fontStyles(font)) {
                    EpubWriter.entry(zip, "EPUB/fonts/" + font.fileName(style), EpubWriter.fontBytes(font, style));
                }
            }
            int width = (int) Math.round(size[0] * PX_PER_MM);
            int height = (int) Math.round(size[1] * PX_PER_MM);
            for (Page page : pages) {
                EpubWriter.text(zip, "EPUB/" + page.file(), xhtml(ebook, page, width, height));
            }
            for (var image : ebook.illustrations().entrySet()) {
                if (PdfWriter.imageUri(ebook, image.getKey()) != null) {
                    EpubWriter.entry(zip, "EPUB/" + href(ebook, image.getKey()), image.getValue().bytes());
                }
            }
            if (ebook.cover() != null) {
                EpubWriter.entry(zip, "EPUB/images/cover." + ebook.cover().extension(), ebook.cover().bytes());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** As páginas na ordem do livro: as que o Booksmith gera (se o autor pediu) e as da história. */
    static List<Page> pages(ExportBook book, double[] size) {
        List<Page> pages = new ArrayList<>();
        java.util.function.Function<String, String> src = id -> "cover".equals(id) && book.cover() != null
                ? "images/cover." + book.cover().extension() : href(book, id);
        // No e-book não há páginas em branco para fechar em múltiplo de 4
        int story = 0;
        int extra = 0;
        for (PictureBookHtml.Part part : PictureBookHtml.sequence(book, size, src, false).parts()) {
            String file = part.story() ? "page-%03d.xhtml".formatted(++story) : "extra-%02d.xhtml".formatted(++extra);
            pages.add(new Page(file, part.title(), part.html(), part.side()));
        }
        return pages;
    }

    private static String href(ExportBook book, String imageId) {
        var image = book.illustrations().get(imageId);
        return image == null ? null : "images/ill-" + imageId + "." + image.extension();
    }

    private static String xhtml(ExportBook book, Page page, int width, int height) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="%1$s" lang="%1$s">
                <head>
                <meta charset="UTF-8"/>
                <meta name="viewport" content="width=%3$d, height=%4$d"/>
                <title>%2$s</title>
                <link rel="stylesheet" type="text/css" href="css/book.css"/>
                </head>
                <body>
                %5$s</body>
                </html>
                """.formatted(book.language(), escape(page.title()), width, height, page.body());
    }

    /** O CSS do PDF sem a regra da página impressa, a página do tamanho do livro e as fontes embutidas. */
    private static String css(double[] size, ExportBook book, java.util.Set<BookFont> fonts) {
        StringBuilder css = new StringBuilder();
        for (BookFont font : fonts) {
            for (String style : EpubWriter.fontStyles(font)) {
                css.append("@font-face { font-family: '%s'; src: url(../fonts/%s); font-weight: %s; font-style: %s; }\n"
                        .formatted(font.family(), font.fileName(style), style.startsWith("Bold") ? "bold" : "normal",
                                style.endsWith("Italic") ? "italic" : "normal"));
            }
        }
        String print = PictureBookHtml.css(size, book.layout().bookFont()).lines()
                .filter(line -> !line.strip().startsWith("@page")).reduce("", (a, b) -> a + b + "\n");
        return css + print + "html, body { width: %smm; height: %smm; margin: 0; padding: 0; overflow: hidden; }\n"
                .formatted(mm(size[0]), mm(size[1]));
    }

    private static String packageDocument(ExportBook book, List<Page> pages, java.util.Set<BookFont> fonts) {
        StringBuilder manifest = new StringBuilder();
        StringBuilder spine = new StringBuilder();
        manifest.append("""
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="css" href="css/book.css" media-type="text/css"/>
                """);
        for (BookFont font : fonts) {
            for (String style : EpubWriter.fontStyles(font)) {
                manifest.append("    <item id=\"font-%s-%s\" href=\"fonts/%s\" media-type=\"font/ttf\"/>\n".formatted(
                        font.id(), style.toLowerCase(Locale.ROOT), font.fileName(style)));
            }
        }
        for (var image : book.illustrations().entrySet()) {
            if (PdfWriter.imageUri(book, image.getKey()) != null) {
                manifest.append("    <item id=\"ill-%s\" href=\"%s\" media-type=\"%s\"/>\n"
                        .formatted(image.getKey(), href(book, image.getKey()), image.getValue().contentType()));
            }
        }
        if (book.cover() != null) {
            manifest.append("    <item id=\"cover-image\" href=\"images/cover.%s\" media-type=\"%s\" properties=\"cover-image\"/>\n"
                    .formatted(book.cover().extension(), book.cover().contentType()));
        }
        for (int i = 0; i < pages.size(); i++) {
            Page page = pages.get(i);
            manifest.append("    <item id=\"p%d\" href=\"%s\" media-type=\"application/xhtml+xml\"/>\n"
                    .formatted(i + 1, page.file()));
            spine.append(page.side() == null ? "    <itemref idref=\"p%d\"/>\n".formatted(i + 1)
                    : "    <itemref idref=\"p%d\" properties=\"page-spread-%s\"/>\n".formatted(i + 1, page.side()));
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id" xml:lang="%1$s">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="book-id">%2$s</dc:identifier>
                    <dc:title>%3$s</dc:title>
                    <dc:creator>%4$s</dc:creator>
                    <dc:language>%1$s</dc:language>
                    <meta property="dcterms:modified">%5$s</meta>
                    <meta property="rendition:layout">pre-paginated</meta>
                    <meta property="rendition:spread">auto</meta>
                    <meta property="rendition:orientation">auto</meta>
                  </metadata>
                  <manifest>
                %6$s  </manifest>
                  <spine>
                %7$s  </spine>
                </package>
                """.formatted(book.language(), identifier(book), escape(book.title()), escape(book.author()),
                MODIFIED.format(book.generatedAt().truncatedTo(ChronoUnit.SECONDS)), manifest, spine);
    }

    private static String identifier(ExportBook book) {
        String isbn = book.pages().isbnEbook();
        return isbn == null || isbn.isBlank() ? "urn:uuid:" + book.id() : "urn:isbn:" + isbn;
    }

    private static String nav(ExportBook book, List<Page> pages) {
        StringBuilder items = new StringBuilder();
        for (Page page : pages) {
            if (page.side() == null || "left".equals(page.side())) {
                items.append("      <li><a href=\"%s\">%s</a></li>\n".formatted(page.file(), escape(page.title())));
            }
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="%1$s" lang="%1$s">
                <head>
                <meta charset="UTF-8"/>
                <title>Sumário</title>
                </head>
                <body>
                <nav epub:type="toc" id="toc">
                  <h1>Sumário</h1>
                  <ol>
                %2$s  </ol>
                </nav>
                </body>
                </html>
                """.formatted(book.language(), items);
    }

    private static String mm(double value) {
        return String.valueOf(Math.round(value * 100) / 100.0);
    }
}
