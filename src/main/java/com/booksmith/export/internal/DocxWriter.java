package com.booksmith.export.internal;

import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.booksmith.export.internal.BookHtml.escape;

/**
 * Manuscrito em Word (.docx), no formato que editoras, concursos e revisores pedem: A4, Times New Roman 12,
 * espaço 1,5, número de página no pé, um capítulo por página. Usa os estilos nativos do Word ("Título",
 * "Subtítulo", "Título 1", "Citação"), então o painel de navegação mostra os capítulos, o corretor do Word usa o
 * idioma do livro e o arquivo volta certo se for importado de novo no Booksmith (§9.3).
 *
 * <p>XML escrito à mão, sem docx4j: o conteúdo é só texto com estilo, e assim não entra uma dependência grande.
 */
final class DocxWriter {

    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    /** Recuo da primeira linha: 1,25 cm, o padrão do Word em português. */
    private static final int FIRST_LINE_INDENT = 709;

    /** Na ordem que o esquema do Word exige dentro de {@code w:rPr} (b, i, strike, u). */
    private static final List<String> MARK_ORDER = List.of("bold", "italic", "strike", "underline");

    private DocxWriter() {
    }

    static byte[] write(ExportBook book) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            entry(zip, "[Content_Types].xml", CONTENT_TYPES);
            entry(zip, "_rels/.rels", ROOT_RELS);
            entry(zip, "docProps/core.xml", core(book));
            entry(zip, "word/_rels/document.xml.rels", DOCUMENT_RELS);
            entry(zip, "word/styles.xml", styles(book.language()));
            entry(zip, "word/settings.xml", SETTINGS);
            entry(zip, "word/footer1.xml", FOOTER);
            entry(zip, "word/document.xml", document(book));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    static String document(ExportBook book) {
        StringBuilder body = new StringBuilder();
        // Capa: título e autor. Tudo em "Subtítulo" logo abaixo do título é tratado como capa na importação
        body.append(paragraph("Title", null, escapedRun(book.title())));
        body.append(paragraph("Subtitle", null, escapedRun(book.author())));
        if (book.fanfic()) {
            body.append(paragraph("Subtitle", null, ""));
            body.append(paragraph("Subtitle", null, escapedRun("Edição de fã")));
            body.append(paragraph("Subtitle", null, escapedRun("Obra de fã baseada em " + book.fandom()
                    + ". Os direitos sobre a obra original, seus personagens e seu universo pertencem aos seus criadores.")));
            body.append(paragraph("Subtitle", null, escapedRun("Cópia para uso pessoal, sem fins comerciais. Não pode ser vendida.")));
        }
        for (ExportBook.Chapter chapter : book.chapters()) {
            body.append(paragraph("Heading1", null, escapedRun(chapter.title())));
            blocks(chapter.document(), null, body);
        }
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="%s" xmlns:r="%s">
                <w:body>
                %s<w:sectPr>
                <w:footerReference w:type="default" r:id="rIdFooter"/>
                <w:pgSz w:w="11906" w:h="16838"/>
                <w:pgMar w:top="1418" w:right="1418" w:bottom="1418" w:left="1418" w:header="709" w:footer="709" w:gutter="0"/>
                <w:titlePg/>
                </w:sectPr>
                </w:body>
                </w:document>
                """.formatted(W, R, body);
    }

    /** {@code style}: o estilo dos parágrafos deste nível (dentro de uma citação, "Quote"). */
    private static void blocks(JsonNode node, String style, StringBuilder body) {
        JsonNode content = node.get("content");
        if (content == null) {
            return;
        }
        int number = 0;
        for (JsonNode child : content) {
            switch (type(child)) {
                case "paragraph" -> {
                    String props = indent(child) ? "<w:ind w:firstLine=\"%d\"/>".formatted(FIRST_LINE_INDENT) : null;
                    body.append(paragraph(style, props, runs(child)));
                }
                case "heading" -> {
                    int level = Math.min(Math.max(intAttr(child, "level", 1), 1), 3) + 1;
                    body.append(paragraph("Heading" + level, null, runs(child)));
                }
                case "blockquote" -> blocks(child, "Quote", body);
                case "sceneBreak" -> body.append(paragraph(style, "<w:jc w:val=\"center\"/>", escapedRun("* * *")));
                case "bulletList", "orderedList" -> blocks(child, style, body);
                case "listItem" -> {
                    // Sem numeração automática do Word: o marcador vai como texto, simples e fiel
                    number++;
                    String marker = "orderedList".equals(type(node))
                            ? (intAttr(node, "start", 1) + number - 1) + ". "
                            : "• ";
                    listItem(child, style, marker, body);
                }
                default -> blocks(child, style, body);
            }
        }
    }

    private static void listItem(JsonNode item, String style, String marker, StringBuilder body) {
        JsonNode content = item.get("content");
        if (content == null) {
            return;
        }
        boolean first = true;
        for (JsonNode child : content) {
            if (first && "paragraph".equals(type(child))) {
                body.append(paragraph(style, "<w:ind w:left=\"567\" w:hanging=\"283\"/>", escapedRun(marker) + runs(child)));
            } else {
                blocks(wrap(child), style, body);
            }
            first = false;
        }
    }

    private static JsonNode wrap(JsonNode child) {
        var node = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        node.putArray("content").add(child);
        return node;
    }

    private static String paragraph(String style, String props, String runs) {
        StringBuilder p = new StringBuilder("<w:p>");
        if (style != null || props != null) {
            p.append("<w:pPr>");
            if (style != null) {
                p.append("<w:pStyle w:val=\"").append(style).append("\"/>");
            }
            if (props != null) {
                p.append(props);
            }
            p.append("</w:pPr>");
        }
        return p.append(runs).append("</w:p>\n").toString();
    }

    private static String runs(JsonNode block) {
        JsonNode content = block.get("content");
        if (content == null) {
            return "";
        }
        StringBuilder runs = new StringBuilder();
        for (JsonNode node : content) {
            if ("hardBreak".equals(type(node))) {
                runs.append("<w:r><w:br/></w:r>");
                continue;
            }
            JsonNode text = BookHtml.textOf(node);
            if (text == null) {
                continue;
            }
            StringBuilder props = new StringBuilder();
            JsonNode marks = node.get("marks");
            if (marks != null) {
                for (String name : MARK_ORDER) {
                    for (JsonNode mark : marks) {
                        if (name.equals(type(mark))) {
                            props.append(switch (name) {
                                case "bold" -> "<w:b/>";
                                case "italic" -> "<w:i/>";
                                case "underline" -> "<w:u w:val=\"single\"/>";
                                default -> "<w:strike/>";
                            });
                        }
                    }
                }
            }
            runs.append("<w:r>");
            if (!props.isEmpty()) {
                runs.append("<w:rPr>").append(props).append("</w:rPr>");
            }
            runs.append("<w:t xml:space=\"preserve\">").append(escape(text.stringValue())).append("</w:t></w:r>");
        }
        return runs.toString();
    }

    private static String escapedRun(String text) {
        return "<w:r><w:t xml:space=\"preserve\">" + escape(text) + "</w:t></w:r>";
    }

    private static boolean indent(JsonNode paragraph) {
        JsonNode attrs = paragraph.get("attrs");
        return attrs != null && attrs.get("indent") != null && attrs.get("indent").asBoolean(false);
    }

    private static String type(JsonNode node) {
        JsonNode type = node.get("type");
        return type == null ? "" : type.stringValue();
    }

    private static int intAttr(JsonNode node, String name, int fallback) {
        JsonNode attrs = node.get("attrs");
        JsonNode value = attrs == null ? null : attrs.get(name);
        return value != null && value.canConvertToInt() ? value.asInt() : fallback;
    }

    private static String core(ExportBook book) {
        String now = DateTimeFormatter.ISO_INSTANT.format(book.generatedAt().truncatedTo(ChronoUnit.SECONDS));
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" \
                xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" \
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                <dc:title>%s</dc:title>
                <dc:creator>%s</dc:creator>
                <dc:language>%s</dc:language>
                <dcterms:created xsi:type="dcterms:W3CDTF">%s</dcterms:created>
                <dcterms:modified xsi:type="dcterms:W3CDTF">%s</dcterms:modified>
                </cp:coreProperties>
                """.formatted(escape(book.title()), escape(book.author()), book.language(), now, now);
    }

    /** Estilos nativos (ids e nomes em inglês, como o Word grava); o Word mostra os nomes no idioma dele. */
    private static String styles(String language) {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:styles xmlns:w="%s">
                <w:docDefaults>
                <w:rPrDefault><w:rPr>
                <w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:eastAsia="Times New Roman" w:cs="Times New Roman"/>
                <w:sz w:val="24"/><w:szCs w:val="24"/><w:lang w:val="%s"/>
                </w:rPr></w:rPrDefault>
                <w:pPrDefault><w:pPr><w:spacing w:after="0" w:line="360" w:lineRule="auto"/><w:jc w:val="both"/></w:pPr></w:pPrDefault>
                </w:docDefaults>
                <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>
                <w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
                <w:pPr><w:spacing w:before="4320" w:after="480"/><w:jc w:val="center"/></w:pPr><w:rPr><w:sz w:val="48"/><w:szCs w:val="48"/></w:rPr></w:style>
                <w:style w:type="paragraph" w:styleId="Subtitle"><w:name w:val="Subtitle"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
                <w:pPr><w:spacing w:after="240"/><w:jc w:val="center"/></w:pPr><w:rPr><w:sz w:val="28"/><w:szCs w:val="28"/></w:rPr></w:style>
                <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
                <w:pPr><w:keepNext/><w:pageBreakBefore/><w:spacing w:before="2880" w:after="720"/><w:jc w:val="center"/><w:outlineLvl w:val="0"/></w:pPr>
                <w:rPr><w:sz w:val="32"/><w:szCs w:val="32"/></w:rPr></w:style>
                <w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
                <w:pPr><w:keepNext/><w:spacing w:before="360" w:after="120"/><w:jc w:val="left"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:sz w:val="26"/></w:rPr></w:style>
                <w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
                <w:pPr><w:keepNext/><w:spacing w:before="240" w:after="120"/><w:jc w:val="left"/><w:outlineLvl w:val="2"/></w:pPr><w:rPr><w:b/></w:rPr></w:style>
                <w:style w:type="paragraph" w:styleId="Heading4"><w:name w:val="heading 4"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
                <w:pPr><w:keepNext/><w:spacing w:before="240" w:after="120"/><w:jc w:val="left"/><w:outlineLvl w:val="3"/></w:pPr><w:rPr><w:b/><w:i/></w:rPr></w:style>
                <w:style w:type="paragraph" w:styleId="Quote"><w:name w:val="Quote"/><w:basedOn w:val="Normal"/><w:qFormat/>
                <w:pPr><w:ind w:left="851" w:right="851"/></w:pPr><w:rPr><w:i/></w:rPr></w:style>
                <w:style w:type="paragraph" w:styleId="Footer"><w:name w:val="footer"/><w:basedOn w:val="Normal"/><w:pPr><w:jc w:val="center"/></w:pPr>
                <w:rPr><w:sz w:val="20"/></w:rPr></w:style>
                </w:styles>
                """.formatted(W, language);
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static final String CONTENT_TYPES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
            <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
            <Default Extension="xml" ContentType="application/xml"/>
            <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
            <Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>
            <Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>
            <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
            </Types>
            """;

    private static final String ROOT_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
            </Relationships>
            """;

    private static final String DOCUMENT_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
            <Relationship Id="rIdSettings" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/>
            <Relationship Id="rIdFooter" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>
            </Relationships>
            """;

    private static final String SETTINGS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
            <w:defaultTabStop w:val="709"/>
            <w:compat><w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/></w:compat>
            </w:settings>
            """;

    /** Número da página centralizado (campo PAGE). A capa não tem número ({@code titlePg} na seção). */
    private static final String FOOTER = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
            <w:p><w:pPr><w:pStyle w:val="Footer"/></w:pPr>
            <w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> PAGE </w:instrText></w:r>
            <w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>1</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r>
            </w:p>
            </w:ftr>
            """;
}
