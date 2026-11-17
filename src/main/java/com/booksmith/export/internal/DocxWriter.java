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
        Images images = new Images(book);
        IMAGES.set(images);
        List<String> notes = new java.util.ArrayList<>();
        NOTES.set(notes);
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            String document = document(book);
            entry(zip, "[Content_Types].xml", CONTENT_TYPES);
            entry(zip, "_rels/.rels", ROOT_RELS);
            entry(zip, "docProps/core.xml", core(book));
            entry(zip, "word/_rels/document.xml.rels", DOCUMENT_RELS.replace("</Relationships>", images.relationships() + "</Relationships>"));
            entry(zip, "word/styles.xml", styles(book.language(), wordFont(book)));
            entry(zip, "word/settings.xml", SETTINGS);
            entry(zip, "word/footer1.xml", FOOTER);
            entry(zip, "word/document.xml", document);
            entry(zip, "word/footnotes.xml", footnotes(notes));
            for (Images.Media media : images.media()) {
                zip.putNextEntry(new ZipEntry("word/media/" + media.file()));
                zip.write(media.bytes());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            IMAGES.remove();
            NOTES.remove();
        }
        return bytes.toByteArray();
    }

    /** As notas de rodapé, na ordem do livro (o Word numera sozinho); o id é a posição + 1. */
    private static final ThreadLocal<List<String>> NOTES = new ThreadLocal<>();

    /** word/footnotes.xml: as duas separadoras que o Word exige (ids -1 e 0) e uma nota por chamada no texto. */
    static String footnotes(List<String> notes) {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:footnotes xmlns:w="%s">
                <w:footnote w:type="separator" w:id="-1"><w:p><w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr><w:r><w:separator/></w:r></w:p></w:footnote>
                <w:footnote w:type="continuationSeparator" w:id="0"><w:p><w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr><w:r><w:continuationSeparator/></w:r></w:p></w:footnote>
                """.formatted(W));
        for (int i = 0; i < notes.size(); i++) {
            xml.append("<w:footnote w:id=\"%d\"><w:p><w:pPr><w:pStyle w:val=\"FootnoteText\"/></w:pPr>".formatted(i + 1))
                    .append("<w:r><w:rPr><w:rStyle w:val=\"FootnoteReference\"/></w:rPr><w:footnoteRef/></w:r>")
                    .append("<w:r><w:t xml:space=\"preserve\"> ").append(escape(notes.get(i))).append("</w:t></w:r></w:p></w:footnote>\n");
        }
        return xml.append("</w:footnotes>\n").toString();
    }

    /** As imagens das ilustrações que vão dentro do .docx (word/media), uma vez cada. */
    private static final ThreadLocal<Images> IMAGES = new ThreadLocal<>();

    /** Largura da área de texto (A4, margens de 2,5 cm) em EMU: 9.070 twips × 635. */
    private static final long TEXT_WIDTH_EMU = 9_070L * 635;
    /** Altura máxima de uma ilustração no Word: 22 cm. */
    private static final long MAX_HEIGHT_EMU = 22L * 360_000;

    private static final class Images {
        record Media(String id, String file, byte[] bytes, int widthPx, int heightPx) {
        }

        private final ExportBook book;
        private final java.util.Map<String, Media> byImage = new java.util.LinkedHashMap<>();
        private int drawings;

        Images(ExportBook book) {
            this.book = book;
        }

        /** A imagem pronta para o Word (PNG ou JPEG que dá para medir), ou null: aí vai o marcador de texto. */
        Media media(String imageId) {
            if (byImage.containsKey(imageId)) {
                return byImage.get(imageId);
            }
            var image = book.illustrations().get(imageId);
            Media media = null;
            if (image != null && PdfWriter.embeds(image.contentType())) {
                int[] px = PrintCheck.pixels(image.bytes());
                if (px[0] > 0 && px[1] > 0) {
                    String ext = "image/png".equals(image.contentType()) ? "png" : "jpeg";
                    int n = byImage.size() + 1;
                    media = new Media("rIdImg" + n, "imagem-" + n + "." + ext, image.bytes(), px[0], px[1]);
                }
            }
            byImage.put(imageId, media);
            return media;
        }

        int nextDrawing() {
            return ++drawings;
        }

        List<Media> media() {
            return byImage.values().stream().filter(java.util.Objects::nonNull).toList();
        }

        String relationships() {
            StringBuilder rels = new StringBuilder();
            for (Media media : media()) {
                rels.append("<Relationship Id=\"%s\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/%s\"/>\n"
                        .formatted(media.id(), media.file()));
            }
            return rels.toString();
        }
    }

    /**
     * A ilustração como imagem do Word (desenho em linha, no tamanho escolhido: pequena 40%, média 65%, as outras
     * na largura do texto; página própria começa página nova), com a legenda embaixo, em itálico. Sem imagem que o
     * Word leia (WebP, ou fora do livro), o marcador "[Ilustração: legenda]" de antes.
     */
    private static void illustration(JsonNode node, String style, StringBuilder body) {
        JsonNode attrs = node.get("attrs");
        JsonNode captionNode = attrs == null ? null : attrs.get("caption");
        String caption = captionNode == null || captionNode.isNull() ? "" : captionNode.stringValue().strip();
        Images images = IMAGES.get();
        JsonNode imageId = attrs == null ? null : attrs.get("imageId");
        Images.Media media = images == null || imageId == null || imageId.isNull() ? null : images.media(imageId.stringValue());
        if (media == null) {
            String text = caption.isEmpty() ? "[Ilustração]" : "[Ilustração: " + caption + "]";
            body.append(paragraph(style, "<w:jc w:val=\"center\"/>", escapedRun(text)));
            return;
        }
        Placement placement = Placement.of(attrs);
        double fraction = placement == Placement.SMALL || placement == Placement.MEDIUM ? placement.fraction : 1;
        long width = Math.round(TEXT_WIDTH_EMU * fraction);
        long height = Math.round(width * (double) media.heightPx() / media.widthPx());
        if (height > MAX_HEIGHT_EMU) {
            width = Math.round(width * (double) MAX_HEIGHT_EMU / height);
            height = MAX_HEIGHT_EMU;
        }
        String align = Placement.align(attrs, placement);
        String jc = "<w:jc w:val=\"%s\"/>".formatted("center".equals(align) ? "center" : align);
        String props = "<w:keepNext/>" + (placement.ownPage() ? "<w:pageBreakBefore/>" : "") + jc;
        int id = images.nextDrawing();
        String descr = escape(caption.isEmpty() ? "Ilustração" : caption);
        String drawing = ("<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">"
                + "<wp:extent cx=\"%1$d\" cy=\"%2$d\"/><wp:docPr id=\"%3$d\" name=\"Ilustração %3$d\" descr=\"%4$s\"/>"
                + "<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>"
                + "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic>"
                + "<pic:nvPicPr><pic:cNvPr id=\"%3$d\" name=\"%5$s\"/><pic:cNvPicPr/></pic:nvPicPr>"
                + "<pic:blipFill><a:blip r:embed=\"%6$s\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>"
                + "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"%1$d\" cy=\"%2$d\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic>"
                + "</wp:inline></w:drawing></w:r>").formatted(width, height, id, descr, media.file(), media.id());
        body.append(paragraph(style, props, drawing));
        if (!caption.isEmpty()) {
            body.append(paragraph(style, jc, "<w:r><w:rPr><w:i/><w:sz w:val=\"20\"/></w:rPr><w:t xml:space=\"preserve\">"
                    + escape(caption) + "</w:t></w:r>"));
        }
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
        if (book.pages().hasHalfTitle()) {
            // Falsa folha de rosto: só o título, numa página; o texto em "Subtítulo", como o resto da capa
            body.append(paragraph("Subtitle", "<w:pageBreakBefore/><w:jc w:val=\"center\"/>", escapedRun(book.title())));
        }
        frontPages(book.pages(), body);
        for (ExportBook.Chapter chapter : book.chapters()) {
            if (chapter.partTitle() != null) {
                body.append(paragraph("Parte", null, escapedRun(chapter.partTitle())));
            }
            body.append(paragraph("Heading1", null, escapedRun(chapter.title())));
            blocks(chapter.reading(), null, body);
        }
        for (BookPagesHtml.Page extra : BookPagesHtml.back(book.pages(), book.author())) {
            body.append(paragraph("PosTextual", null, escapedRun(extra.title())));
            String text = switch (extra.id()) {
                case "acknowledgments" -> book.pages().acknowledgments();
                case "colophon" -> book.pages().colophon();
                default -> book.pages().aboutAuthor();
            };
            if ("about-author".equals(extra.id()) && book.pages().authorPhotoId() != null) {
                // A foto de quem escreveu, pequena e no meio, antes do texto
                var photo = tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode();
                photo.put("type", "illustration");
                photo.putObject("attrs").put("imageId", book.pages().authorPhotoId().toString()).put("size", "small");
                illustration(photo, null, body);
            }
            for (String line : lines(text)) {
                body.append(paragraph(null, "<w:ind w:firstLine=\"%d\"/>".formatted(FIRST_LINE_INDENT), escapedRun(line)));
            }
        }
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="%s" xmlns:r="%s" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
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

    /**
     * Créditos, dedicatória e epígrafe, cada um na sua página. Em "Subtítulo", como o resto da capa: assim a
     * importação do arquivo os reconhece como capa e não cria um capítulo com eles.
     */
    private static void frontPages(com.booksmith.manuscript.api.Manuscripts.BookPages pages, StringBuilder body) {
        List<String> credits = new java.util.ArrayList<>(lines(pages.credits()));
        if (pages.catalogCard() != null) {
            // A ficha catalográfica no fim dos créditos, como nos livros
            credits.add("");
            credits.addAll(lines(pages.catalogCard()));
        }
        page(credits.stream().filter(l -> !l.isEmpty()).toList(), "<w:jc w:val=\"left\"/>", body);
        page(lines(pages.dedication()), "<w:jc w:val=\"right\"/>", body);
        List<String> epigraph = new java.util.ArrayList<>(lines(pages.epigraph()));
        if (!epigraph.isEmpty() && pages.epigraphSource() != null) {
            epigraph.add("— " + pages.epigraphSource());
        }
        page(epigraph, "<w:jc w:val=\"right\"/>", body);
    }

    /** Parágrafos em "Subtítulo", o primeiro começando página nova. */
    private static void page(List<String> lines, String align, StringBuilder body) {
        for (int i = 0; i < lines.size(); i++) {
            String props = (i == 0 ? "<w:pageBreakBefore/>" : "") + align;
            body.append(paragraph("Subtitle", props, escapedRun(lines.get(i))));
        }
    }

    /** As linhas com texto (null = nenhuma). */
    private static List<String> lines(String text) {
        return text == null ? List.of() : text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
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
                case "callout" -> {
                    // Quadro de destaque: o nome do tipo em negrito e o conteúdo, no estilo "Destaque" (borda e fundo)
                    String kind = child.get("attrs") == null || child.get("attrs").get("kind") == null
                            ? "important" : child.get("attrs").get("kind").stringValue();
                    body.append(paragraph("Destaque", "<w:keepNext/>",
                            "<w:r><w:rPr><w:b/><w:caps/><w:sz w:val=\"18\"/></w:rPr><w:t xml:space=\"preserve\">"
                                    + escape(BookHtml.calloutLabel(kind)) + "</w:t></w:r>"));
                    blocks(child, "Destaque", body);
                }
                case "sceneBreak" -> body.append(paragraph(style, "<w:jc w:val=\"center\"/>", escapedRun("* * *")));
                case "illustration" -> illustration(child, style, body);
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
            if ("footnote".equals(type(node))) {
                List<String> notes = NOTES.get();
                JsonNode attrs = node.get("attrs");
                String note = attrs == null || attrs.get("text") == null ? "" : attrs.get("text").stringValue().strip();
                if (notes != null && !note.isEmpty()) {
                    notes.add(note);
                    runs.append("<w:r><w:rPr><w:rStyle w:val=\"FootnoteReference\"/></w:rPr><w:footnoteReference w:id=\"%d\"/></w:r>"
                            .formatted(notes.size()));
                }
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
    /** A fonte escolhida no livro; a padrão vira Times New Roman (o padrão de original para editora). */
    static String wordFont(ExportBook book) {
        com.booksmith.manuscript.api.BookFont font = book.layout().bookFont();
        return font == com.booksmith.manuscript.api.BookFont.DEFAULT ? "Times New Roman" : font.family();
    }

    private static String styles(String language, String font) {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:styles xmlns:w="%s">
                <w:docDefaults>
                <w:rPrDefault><w:rPr>
                <w:rFonts w:ascii="%3$s" w:hAnsi="%3$s" w:eastAsia="%3$s" w:cs="%3$s"/>
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
                <w:style w:type="paragraph" w:styleId="Parte"><w:name w:val="Parte"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
                <w:pPr><w:pageBreakBefore/><w:spacing w:before="4320" w:after="0"/><w:jc w:val="center"/></w:pPr>
                <w:rPr><w:b/><w:sz w:val="36"/><w:szCs w:val="36"/></w:rPr></w:style>
                <w:style w:type="paragraph" w:styleId="PosTextual"><w:name w:val="Pós-textual"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
                <w:pPr><w:keepNext/><w:pageBreakBefore/><w:spacing w:before="2880" w:after="720"/><w:jc w:val="center"/></w:pPr>
                <w:rPr><w:sz w:val="32"/><w:szCs w:val="32"/></w:rPr></w:style>
                <w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
                <w:pPr><w:keepNext/><w:spacing w:before="360" w:after="120"/><w:jc w:val="left"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:sz w:val="26"/></w:rPr></w:style>
                <w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
                <w:pPr><w:keepNext/><w:spacing w:before="240" w:after="120"/><w:jc w:val="left"/><w:outlineLvl w:val="2"/></w:pPr><w:rPr><w:b/></w:rPr></w:style>
                <w:style w:type="paragraph" w:styleId="Heading4"><w:name w:val="heading 4"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
                <w:pPr><w:keepNext/><w:spacing w:before="240" w:after="120"/><w:jc w:val="left"/><w:outlineLvl w:val="3"/></w:pPr><w:rPr><w:b/><w:i/></w:rPr></w:style>
                <w:style w:type="paragraph" w:styleId="Quote"><w:name w:val="Quote"/><w:basedOn w:val="Normal"/><w:qFormat/>
                <w:pPr><w:ind w:left="851" w:right="851"/></w:pPr><w:rPr><w:i/></w:rPr></w:style>
                <w:style w:type="paragraph" w:styleId="Destaque"><w:name w:val="Destaque"/><w:basedOn w:val="Normal"/><w:pPr><w:pBdr><w:left w:val="single" w:sz="24" w:space="8" w:color="C8743A"/></w:pBdr><w:shd w:val="clear" w:color="auto" w:fill="F6EFE6"/><w:ind w:left="284" w:firstLine="0"/><w:jc w:val="left"/></w:pPr></w:style>
                <w:style w:type="paragraph" w:styleId="FootnoteText"><w:name w:val="footnote text"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/><w:ind w:firstLine="0"/><w:jc w:val="left"/></w:pPr><w:rPr><w:sz w:val="20"/></w:rPr></w:style>
                <w:style w:type="character" w:styleId="FootnoteReference"><w:name w:val="footnote reference"/><w:rPr><w:vertAlign w:val="superscript"/></w:rPr></w:style>
                <w:style w:type="paragraph" w:styleId="Footer"><w:name w:val="footer"/><w:basedOn w:val="Normal"/><w:pPr><w:jc w:val="center"/></w:pPr>
                <w:rPr><w:sz w:val="20"/></w:rPr></w:style>
                </w:styles>
                """.formatted(W, language, font);
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
            <Default Extension="png" ContentType="image/png"/>
            <Default Extension="jpeg" ContentType="image/jpeg"/>
            <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
            <Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>
            <Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>
            <Override PartName="/word/footnotes.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footnotes+xml"/>
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
            <Relationship Id="rIdFootnotes" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footnotes" Target="footnotes.xml"/>
            </Relationships>
            """;

    private static final String SETTINGS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
            <w:defaultTabStop w:val="709"/>
            <w:footnotePr><w:footnote w:id="-1"/><w:footnote w:id="0"/></w:footnotePr>
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
