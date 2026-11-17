package com.booksmith.export.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.booksmith.export.internal.BookHtml.escape;

/**
 * ePub 3 (§13.1): XHTML por capítulo + package.opf + nav, num zip com o {@code mimetype} primeiro e sem
 * compressão. Leva também o toc.ncx, para leitores antigos. Validado com EPUBCheck nos testes.
 */
final class EpubWriter {

    private static final DateTimeFormatter MODIFIED = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    private EpubWriter() {
    }

    static byte[] write(ExportBook book) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            stored(zip, "mimetype", "application/epub+zip".getBytes(StandardCharsets.US_ASCII));
            text(zip, "META-INF/container.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                    """);
            text(zip, "EPUB/package.opf", packageDocument(book));
            text(zip, "EPUB/nav.xhtml", nav(book));
            text(zip, "EPUB/toc.ncx", ncx(book));
            text(zip, "EPUB/css/book.css", CSS);
            if (book.cover() != null) {
                entry(zip, "EPUB/images/cover." + book.cover().extension(), book.cover().bytes());
                text(zip, "EPUB/cover.xhtml", page(book, "Capa", "cover", """
                        <section epub:type="cover" class="cover">
                        <img src="images/cover.%s" alt="Capa de %s"/>
                        </section>
                        """.formatted(book.cover().extension(), escape(book.title()))));
            }
            text(zip, "EPUB/title.xhtml", page(book, book.title(), "titlepage", titlePage(book)));
            if (book.fanfic()) {
                text(zip, "EPUB/fan-notice.xhtml", page(book, "Aviso", "frontmatter", fanNotice(book)));
            }
            for (BookPagesHtml.Page extra : BookPagesHtml.front(book.pages(), book.pages().isbnEbook(), book.title())) {
                text(zip, "EPUB/" + extra.id() + ".xhtml", page(book, extra.title(), "frontmatter", extra.html()));
            }
            for (int i = 0; i < book.chapters().size(); i++) {
                ExportBook.Chapter chapter = book.chapters().get(i);
                if (chapter.partTitle() != null) {
                    text(zip, "EPUB/" + partFile(i), page(book, chapter.partTitle(), "bodymatter", """
                            <section epub:type="part" class="part-page" id="part-%d">
                            <h1 class="part-title">%s</h1>
                            </section>
                            """.formatted(i + 1, escape(chapter.partTitle()))));
                }
                text(zip, "EPUB/" + chapterFile(i), page(book, chapter.title(), "bodymatter", """
                        <section epub:type="chapter" id="chapter-%d">
                        <h1>%s</h1>
                        %s</section>
                        """.formatted(i + 1, escape(chapter.title()),
                        chapter.html(id -> illustrationHref(book, id), "chapter-" + (i + 1)))));
            }
            for (BookPagesHtml.Page extra : back(book)) {
                text(zip, "EPUB/" + extra.id() + ".xhtml", page(book, extra.title(), "backmatter", extra.html()));
            }
            for (var image : book.illustrations().entrySet()) {
                entry(zip, "EPUB/" + illustrationHref(book, image.getKey()), image.getValue().bytes());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** "images/ill-<id>.png", ou null se a imagem não veio. */
    static String illustrationHref(ExportBook book, String imageId) {
        var image = book.illustrations().get(imageId);
        return image == null ? null : "images/ill-" + imageId + "." + image.extension();
    }

    /** Página de abertura da parte que o capítulo {@code index} abre. */
    static String partFile(int index) {
        return "part-%03d.xhtml".formatted(index + 1);
    }

    static String chapterFile(int index) {
        return "chapter-%03d.xhtml".formatted(index + 1);
    }

    private static String packageDocument(ExportBook book) {
        StringBuilder manifest = new StringBuilder();
        StringBuilder spine = new StringBuilder();
        manifest.append("""
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="css" href="css/book.css" media-type="text/css"/>
                """);
        if (book.cover() != null) {
            manifest.append("""
                        <item id="cover-image" href="images/cover.%s" media-type="%s" properties="cover-image"/>
                        <item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>
                    """.formatted(book.cover().extension(), book.cover().contentType()));
            spine.append("    <itemref idref=\"cover\"/>\n");
        }
        for (var image : book.illustrations().entrySet()) {
            manifest.append("    <item id=\"ill-%s\" href=\"%s\" media-type=\"%s\"/>\n"
                    .formatted(image.getKey(), illustrationHref(book, image.getKey()), image.getValue().contentType()));
        }
        manifest.append("    <item id=\"title\" href=\"title.xhtml\" media-type=\"application/xhtml+xml\"/>\n");
        spine.append("    <itemref idref=\"title\"/>\n");
        if (book.fanfic()) {
            manifest.append("    <item id=\"fan-notice\" href=\"fan-notice.xhtml\" media-type=\"application/xhtml+xml\"/>\n");
            spine.append("    <itemref idref=\"fan-notice\"/>\n");
        }
        extras(BookPagesHtml.front(book.pages(), book.pages().isbnEbook(), book.title()), manifest, spine);
        for (int i = 0; i < book.chapters().size(); i++) {
            if (book.chapters().get(i).partTitle() != null) {
                manifest.append("    <item id=\"part-%d\" href=\"%s\" media-type=\"application/xhtml+xml\"/>\n"
                        .formatted(i + 1, partFile(i)));
                spine.append("    <itemref idref=\"part-%d\"/>\n".formatted(i + 1));
            }
            manifest.append("    <item id=\"chapter-%d\" href=\"%s\" media-type=\"application/xhtml+xml\"/>\n"
                    .formatted(i + 1, chapterFile(i)));
            spine.append("    <itemref idref=\"chapter-%d\"/>\n".formatted(i + 1));
        }
        extras(back(book), manifest, spine);
        String description = book.synopsis() == null || book.synopsis().isBlank()
                ? ""
                : "    <dc:description>" + escape(book.synopsis().strip()) + "</dc:description>\n";
        String coverMeta = book.cover() == null ? "" : "    <meta name=\"cover\" content=\"cover-image\"/>\n";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id" xml:lang="%1$s">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="book-id">%2$s</dc:identifier>
                    <dc:title>%3$s</dc:title>
                    <dc:creator>%4$s</dc:creator>
                    <dc:language>%1$s</dc:language>
                %5$s    <meta property="dcterms:modified">%6$s</meta>
                %7$s  </metadata>
                  <manifest>
                %8$s  </manifest>
                  <spine toc="ncx">
                %9$s  </spine>
                </package>
                """.formatted(book.language(), identifier(book), escape(book.title()), escape(book.author()), description,
                MODIFIED.format(book.generatedAt().truncatedTo(ChronoUnit.SECONDS)), coverMeta, manifest, spine);
    }

    private static List<BookPagesHtml.Page> back(ExportBook book) {
        var photo = book.pages().authorPhotoId();
        return BookPagesHtml.back(book.pages(), book.author(), photo == null ? null : illustrationHref(book, photo.toString()));
    }

    private static void extras(List<BookPagesHtml.Page> pages, StringBuilder manifest, StringBuilder spine) {
        for (BookPagesHtml.Page extra : pages) {
            manifest.append("    <item id=\"%1$s\" href=\"%1$s.xhtml\" media-type=\"application/xhtml+xml\"/>\n"
                    .formatted(extra.id()));
            spine.append("    <itemref idref=\"%s\"/>\n".formatted(extra.id()));
        }
    }

    private static String nav(ExportBook book) {
        // Capítulos de uma parte ficam aninhados nela; os de antes da primeira parte, soltos
        StringBuilder items = new StringBuilder();
        boolean inPart = false;
        for (int i = 0; i < book.chapters().size(); i++) {
            ExportBook.Chapter chapter = book.chapters().get(i);
            if (chapter.partTitle() != null) {
                if (inPart) {
                    items.append("        </ol>\n      </li>\n");
                }
                items.append("      <li><a href=\"%s\">%s</a>\n        <ol>\n".formatted(partFile(i), escape(chapter.partTitle())));
                inPart = true;
            }
            String indent = inPart ? "    " : "";
            items.append("      %s<li><a href=\"%s\">%s</a>".formatted(indent, chapterFile(i), escape(chapter.title())));
            List<BookHtml.Section> sections = chapter.sections();
            if (!sections.isEmpty()) {
                // Os subcapítulos, aninhados no capítulo
                items.append("\n      ").append(indent).append("  <ol class=\"sections\">\n");
                for (BookHtml.Section section : sections) {
                    items.append("      %s    <li><a href=\"%s#%s\">%s</a></li>\n".formatted(indent, chapterFile(i),
                            BookHtml.sectionId("chapter-" + (i + 1), section.number()), escape(section.title())));
                }
                items.append("      ").append(indent).append("  </ol>\n      ").append(indent);
            }
            items.append("</li>\n");
        }
        if (inPart) {
            items.append("        </ol>\n      </li>\n");
        }
        for (BookPagesHtml.Page extra : back(book)) {
            items.append("      <li><a href=\"%s.xhtml\">%s</a></li>\n".formatted(extra.id(), escape(extra.title())));
        }
        String landmarks = """
                <nav epub:type="landmarks" hidden="hidden">
                  <ol>
                    <li><a epub:type="titlepage" href="title.xhtml">Folha de rosto</a></li>
                    <li><a epub:type="bodymatter" href="%s">Início</a></li>
                  </ol>
                </nav>
                """.formatted(chapterFile(0));
        return page(book, "Sumário", "frontmatter", """
                <nav epub:type="toc" id="toc">
                  <h1>Sumário</h1>
                  <ol>
                %s  </ol>
                </nav>
                %s""".formatted(items, landmarks));
    }

    private static String ncx(ExportBook book) {
        StringBuilder points = new StringBuilder();
        int order = 0;
        boolean inPart = false;
        for (int i = 0; i < book.chapters().size(); i++) {
            ExportBook.Chapter chapter = book.chapters().get(i);
            if (chapter.partTitle() != null) {
                if (inPart) {
                    points.append("    </navPoint>\n");
                }
                order++;
                points.append("""
                            <navPoint id="np-part-%1$d" playOrder="%2$d">
                              <navLabel><text>%3$s</text></navLabel>
                              <content src="%4$s"/>
                        """.formatted(i + 1, order, escape(chapter.partTitle()), partFile(i)));
                inPart = true;
            }
            order++;
            points.append("""
                        <navPoint id="np-%1$d" playOrder="%2$d">
                          <navLabel><text>%3$s</text></navLabel>
                          <content src="%4$s"/>
                        </navPoint>
                    """.formatted(i + 1, order, escape(chapter.title()), chapterFile(i)));
        }
        if (inPart) {
            points.append("    </navPoint>\n");
        }
        for (BookPagesHtml.Page extra : back(book)) {
            order++;
            points.append("""
                        <navPoint id="np-%1$s" playOrder="%2$d">
                          <navLabel><text>%3$s</text></navLabel>
                          <content src="%1$s.xhtml"/>
                        </navPoint>
                    """.formatted(extra.id(), order, escape(extra.title())));
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <head>
                    <meta name="dtb:uid" content="%s"/>
                  </head>
                  <docTitle><text>%s</text></docTitle>
                  <navMap>
                %s  </navMap>
                </ncx>
                """.formatted(identifier(book), escape(book.title()), points);
    }

    /** Com ISBN do e-book, o ePub é identificado por ele (as lojas leem daqui); sem, pelo id do livro. */
    static String identifier(ExportBook book) {
        String isbn = book.pages().isbnEbook();
        return isbn == null ? "urn:uuid:" + book.id() : "urn:isbn:" + com.booksmith.manuscript.api.Publishing.digits(isbn);
    }

    private static String titlePage(ExportBook book) {
        return """
                <section epub:type="titlepage" class="title-page">
                <h1 class="book-title">%s</h1>
                <p class="book-author">%s</p>
                </section>
                """.formatted(escape(book.title()), escape(book.author()));
    }

    static String fanNotice(ExportBook book) {
        return """
                <section epub:type="preface" class="fan-notice">
                <h1>Edição de fã</h1>
                <p>Esta é uma obra de fã baseada em %s. Todos os direitos sobre a obra original, seus personagens e seu \
                universo pertencem aos seus criadores.</p>
                <p>Cópia para uso pessoal, sem fins comerciais. Não pode ser vendida.</p>
                </section>
                """.formatted(escape(book.fandom()));
    }

    private static String page(ExportBook book, String title, String bodyType, String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="%1$s" lang="%1$s">
                <head>
                <meta charset="UTF-8"/>
                <title>%2$s</title>
                <link rel="stylesheet" type="text/css" href="css/book.css"/>
                </head>
                <body epub:type="%3$s">
                %4$s</body>
                </html>
                """.formatted(book.language(), escape(title), bodyType, body);
    }

    private static void text(ZipOutputStream zip, String name, String content) throws IOException {
        entry(zip, name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void entry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    /** O {@code mimetype} vai sem compressão, como a especificação exige. */
    private static void stored(ZipOutputStream zip, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(content.length);
        entry.setCompressedSize(content.length);
        CRC32 crc = new CRC32();
        crc.update(content);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    /** CSS discreto: o leitor de ePub decide fonte e tamanho; aqui só a tipografia de livro. */
    private static final String CSS = """
            body { margin: 0; padding: 0; }
            p { margin: 0; text-indent: 0; line-height: 1.5; text-align: justify; }
            p.indent { text-indent: 1.5em; }
            p.blank { margin: 0; }
            p.scene-break { text-align: center; margin: 1.2em 0; text-indent: 0; letter-spacing: .3em; }
            h1 { font-size: 1.5em; text-align: center; margin: 2.5em 0 1.5em; font-weight: normal; }
            h2 { font-size: 1.25em; margin: 1.5em 0 .75em; }
            h3, h4 { font-size: 1.1em; margin: 1.2em 0 .6em; }
            blockquote { margin: 1em 1.5em; font-style: italic; }
            blockquote em { font-style: normal; }
            figure.illustration { margin: 1.5em 0; text-align: center; page-break-inside: avoid; }
            figure.illustration img { max-width: 100%; max-height: 80vh; }
            figure.illustration figcaption { font-size: .85em; font-style: italic; margin-top: .5em; text-align: center; }
            figure.size-small { width: 40%; margin-left: auto; margin-right: auto; }
            figure.size-medium { width: 65%; margin-left: auto; margin-right: auto; }
            figure.size-small img, figure.size-medium img, figure.size-wide img { width: 100%; }
            figure.align-left { float: left; margin: .2em 1.2em .6em 0; }
            figure.align-right { float: right; margin: .2em 0 .6em 1.2em; }
            figure.size-page, figure.size-bleed, figure.size-spread { page-break-before: always; page-break-after: always; margin: 0; }
            figure.size-page img, figure.size-bleed img, figure.size-spread img { width: 100%; max-height: 95vh; object-fit: contain; }
            .part-page { text-align: center; padding-top: 35%; }
            .part-title { font-size: 1.8em; margin: 0; font-weight: normal; letter-spacing: .03em; }
            .cover { text-align: center; margin: 0; padding: 0; }
            .cover img { max-width: 100%; max-height: 100%; }
            .title-page { text-align: center; padding-top: 30%; }
            .book-title { font-size: 2em; margin: 0 0 1em; }
            .book-author { text-align: center; font-size: 1.2em; }
            .fan-notice p { margin: 0 0 1em; text-align: left; }
            .credits { padding-top: 60%; font-size: .85em; }
            .credits p, .dedication p, .epigraph p, .back-page p { margin: 0 0 .6em; }
            .credits p { text-align: left; }
            .dedication { padding-top: 35%; margin-left: 30%; font-style: italic; }
            .dedication p { text-align: right; }
            .epigraph { padding-top: 35%; margin-left: 25%; }
            .epigraph-text p { font-style: italic; text-align: left; }
            p.epigraph-source { text-align: right; font-size: .9em; }
            .back-page p { text-indent: 1.5em; }
            nav ol { list-style: none; padding: 0; }
            nav ol.sections { padding-left: 1.2em; font-size: .92em; }
            a.noteref { vertical-align: super; font-size: .7em; line-height: 0; text-decoration: none; }
            .half-title { text-align: center; margin-top: 30%; font-size: 1.3em; }
            .catalog-card { margin-top: 1.5em; border: 1px solid #555; padding: .6em .8em; font-size: .75em; }
            .catalog-card p { text-indent: 0; margin: 0; text-align: left; }
            .author-photo { text-align: center; text-indent: 0; }
            .author-photo img { width: 40%; max-width: 12em; }
            .colophon { margin-top: 40%; text-align: center; font-size: .8em; }
            .colophon p { text-indent: 0; text-align: center; }
            section.footnotes { margin-top: 2em; border-top: 1px solid #999; padding-top: .5em; font-size: .85em; }
            aside.footnote p { text-indent: 0; margin: 0 0 .4em; }
            nav li { margin: .4em 0; }
            """;
}
