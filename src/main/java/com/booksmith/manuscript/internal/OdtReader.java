package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.internal.ImportedText.Paragraph;
import com.booksmith.manuscript.internal.ImportedText.Result;
import com.booksmith.manuscript.internal.ImportedText.Segment;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê um documento de texto OpenDocument (.odt, do LibreOffice e do "Fazer download" do Google Docs): um ZIP com
 * {@code content.xml} (texto e estilos automáticos) e {@code styles.xml} (estilos nomeados).
 * <p>
 * Guarda o mesmo que o leitor de .docx: parágrafos, títulos ({@code text:h}), estilos "Title"/"Subtitle",
 * recuo da primeira linha, quebras de linha, negrito, itálico, sublinhado e tachado, inclusive herdados de
 * estilos. Imagens, notas de rodapé, comentários, sumários gerados e alterações excluídas ficam de fora.
 * Proteções de ZIP e XML em {@link ImportFiles}.
 */
final class OdtReader {

    static final String MIMETYPE = "application/vnd.oasis.opendocument.text";
    private static final String TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";
    private static final String STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0";
    private static final String FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0";
    private static final String OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";
    private static final String DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0";
    private static final String TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";

    /** Elementos de texto cujo conteúdo não é texto do livro (gerado, comentário, excluído). */
    private static final Set<String> SKIPPED_TEXT = Set.of(
            "tracked-changes", "table-of-content", "alphabetical-index", "illustration-index", "table-index",
            "object-index", "user-index", "bibliography", "sequence-decls", "variable-decls", "user-field-decls",
            "deletion", "note-citation");
    private static final Pattern LENGTH = Pattern.compile("^(-?[0-9]*\\.?[0-9]+)");
    private static final Pattern WHITESPACE = Pattern.compile("[ \\t\\r\\n]+");

    private OdtReader() {
    }

    static Result read(byte[] file) {
        Map<String, byte[]> parts = ImportFiles.unzip(file, Set.of("content.xml", "styles.xml"), OdtReader::invalid);
        byte[] content = parts.get("content.xml");
        if (content == null) {
            throw invalid();
        }
        Styles styles = new Styles();
        if (parts.containsKey("styles.xml")) {
            new Parser(styles, false).parse(parts.get("styles.xml"));
        }
        return new Parser(styles, true).parse(content);
    }

    static ApiException invalid() {
        return ApiException.unprocessable("odt_invalid",
                "Não conseguimos ler este arquivo .odt. No LibreOffice, abra o arquivo e use \"Salvar como\" "
                        + "em .odt ou .docx.");
    }

    // ===== Estilos =====

    /** Propriedades que importam; {@code null} quando o estilo não define e herda do pai. */
    private static final class Style {
        String name;
        String displayName;
        String parent;
        Integer outlineLevel;
        Boolean indent;
        final Map<String, Boolean> marks = new HashMap<>();
    }

    private static final class Styles {
        /** Por família ("paragraph", "text") e nome. */
        private final Map<String, Style> byKey = new HashMap<>();

        void add(String family, Style style) {
            byKey.put(family + "/" + style.name, style);
        }

        private Style get(String family, String name) {
            return name == null ? null : byKey.get(family + "/" + name);
        }

        Boolean mark(String family, String name, String mark) {
            Style style = get(family, name);
            for (int hops = 0; style != null && hops < 10; hops++) {
                Boolean value = style.marks.get(mark);
                if (value != null) {
                    return value;
                }
                style = get(family, style.parent);
            }
            return null;
        }

        boolean indent(String name) {
            Style style = get("paragraph", name);
            for (int hops = 0; style != null && hops < 10; hops++) {
                if (style.indent != null) {
                    return style.indent;
                }
                style = get("paragraph", style.parent);
            }
            return false;
        }

        int outlineLevel(String name) {
            Style style = get("paragraph", name);
            for (int hops = 0; style != null && hops < 10; hops++) {
                if (style.outlineLevel != null) {
                    return style.outlineLevel;
                }
                style = get("paragraph", style.parent);
            }
            return 0;
        }

        /** O estilo é "Title" (ou "Subtitle") ou herda dele: os nomes internos do LibreOffice não são traduzidos. */
        boolean is(String name, String wanted) {
            Style style = get("paragraph", name);
            for (int hops = 0; style != null && hops < 10; hops++) {
                if (wanted.equalsIgnoreCase(style.name) || wanted.equalsIgnoreCase(style.displayName)) {
                    return true;
                }
                style = get("paragraph", style.parent);
            }
            return false;
        }
    }

    // ===== Leitura =====

    private static final class Parser {

        private static final List<String> MARKS = List.of("bold", "italic", "underline", "strike");

        private final Styles styles;
        private final boolean body;
        private final List<Paragraph> paragraphs = new ArrayList<>();
        private int images;
        private int tables;
        private int footnotes;

        // Estilo sendo lido
        private Style style;
        private String styleFamily;

        // Parágrafo atual
        private int paragraphDepth;
        private String paragraphStyle;
        private int headingLevel;
        private List<Segment> segments;
        /** Estilos de caractere dos {@code text:span} abertos, do mais externo ao mais interno. */
        private final Deque<String> spans = new ArrayDeque<>();

        Parser(Styles styles, boolean body) {
            this.styles = styles;
            this.body = body;
        }

        Result parse(byte[] bytes) {
            try {
                XMLStreamReader reader = ImportFiles.xml(bytes);
                while (reader.hasNext()) {
                    int event = reader.next();
                    switch (event) {
                        case XMLStreamConstants.START_ELEMENT -> start(reader);
                        case XMLStreamConstants.END_ELEMENT -> end(reader.getNamespaceURI(), reader.getLocalName());
                        case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> characters(reader.getText());
                        default -> {
                            // comentários e instruções: nada a fazer
                        }
                    }
                }
            } catch (XMLStreamException e) {
                throw invalid();
            }
            return new Result(List.copyOf(paragraphs), images, tables, footnotes);
        }

        private void start(XMLStreamReader reader) throws XMLStreamException {
            String ns = reader.getNamespaceURI();
            String name = reader.getLocalName();

            if (STYLE.equals(ns)) {
                startStyle(reader, name);
                return;
            }
            if (!body) {
                return;
            }
            if (DRAW.equals(ns) && (name.equals("frame") || name.equals("custom-shape") || name.equals("g"))) {
                images++;
                ImportFiles.skipElement(reader);
                return;
            }
            if (OFFICE.equals(ns) && (name.equals("annotation") || name.equals("forms"))) {
                ImportFiles.skipElement(reader);
                return;
            }
            if (TABLE.equals(ns) && name.equals("table")) {
                tables++;
                return;
            }
            if (!TEXT.equals(ns)) {
                return;
            }
            if (name.equals("note")) {
                footnotes++;
                ImportFiles.skipElement(reader);
                return;
            }
            if (SKIPPED_TEXT.contains(name)) {
                ImportFiles.skipElement(reader);
                return;
            }
            switch (name) {
                case "p" -> startParagraph(reader.getAttributeValue(TEXT, "style-name"), 0);
                case "h" -> {
                    Integer level = parseInt(reader.getAttributeValue(TEXT, "outline-level"));
                    startParagraph(reader.getAttributeValue(TEXT, "style-name"), level == null ? 1 : Math.max(1, level));
                }
                case "span" -> spans.push(nonNull(reader.getAttributeValue(TEXT, "style-name")));
                case "s" -> {
                    Integer count = parseInt(reader.getAttributeValue(TEXT, "c"));
                    add(" ".repeat(count == null ? 1 : Math.clamp(count, 1, 100)));
                }
                case "tab" -> add("\t");
                case "line-break" -> {
                    if (segments != null) {
                        segments.add(Segment.lineBreak());
                    }
                }
                default -> {
                    // links, marcadores, listas: o texto de dentro entra normalmente
                }
            }
        }

        private void startStyle(XMLStreamReader reader, String name) {
            switch (name) {
                case "style" -> {
                    style = new Style();
                    style.name = reader.getAttributeValue(STYLE, "name");
                    style.displayName = reader.getAttributeValue(STYLE, "display-name");
                    style.parent = reader.getAttributeValue(STYLE, "parent-style-name");
                    Integer outline = parseInt(reader.getAttributeValue(STYLE, "default-outline-level"));
                    style.outlineLevel = outline != null && outline > 0 ? outline : null;
                    styleFamily = reader.getAttributeValue(STYLE, "family");
                }
                case "paragraph-properties" -> {
                    String indent = reader.getAttributeValue(FO, "text-indent");
                    if (style != null && indent != null) {
                        style.indent = positive(indent);
                    }
                }
                case "text-properties" -> {
                    if (style == null) {
                        return;
                    }
                    String weight = reader.getAttributeValue(FO, "font-weight");
                    if (weight != null) {
                        style.marks.put("bold", weight.equals("bold") || weight.matches("[6-9]00"));
                    }
                    String fontStyle = reader.getAttributeValue(FO, "font-style");
                    if (fontStyle != null) {
                        style.marks.put("italic", fontStyle.equals("italic") || fontStyle.equals("oblique"));
                    }
                    String underline = reader.getAttributeValue(STYLE, "text-underline-style");
                    if (underline != null) {
                        style.marks.put("underline", !underline.equals("none"));
                    }
                    String strike = reader.getAttributeValue(STYLE, "text-line-through-style");
                    if (strike != null) {
                        style.marks.put("strike", !strike.equals("none"));
                    }
                }
                default -> {
                    // outras propriedades de estilo não mudam o texto
                }
            }
        }

        private void end(String ns, String name) {
            if (STYLE.equals(ns) && name.equals("style")) {
                if (style != null && style.name != null && styleFamily != null) {
                    styles.add(styleFamily, style);
                }
                style = null;
                return;
            }
            if (!body || !TEXT.equals(ns)) {
                return;
            }
            switch (name) {
                case "p", "h" -> endParagraph();
                case "span" -> {
                    if (!spans.isEmpty()) {
                        spans.pop();
                    }
                }
                default -> {
                    // nada a fazer
                }
            }
        }

        private void startParagraph(String style, int level) {
            paragraphDepth++;
            if (paragraphDepth > 1) {
                return; // parágrafo dentro de parágrafo (caixa de texto): o texto entra no de fora
            }
            paragraphStyle = style;
            headingLevel = level > 0 ? level : styles.outlineLevel(style);
            segments = new ArrayList<>();
            spans.clear();
        }

        private void endParagraph() {
            paragraphDepth--;
            if (paragraphDepth > 0 || segments == null) {
                return;
            }
            List<Segment> cleaned = ImportedText.merge(segments);
            boolean indent = styles.indent(paragraphStyle) || ImportedText.startsWithTab(cleaned);
            cleaned = ImportedText.trim(cleaned);
            boolean title = styles.is(paragraphStyle, "Title");
            boolean subtitle = !title && styles.is(paragraphStyle, "Subtitle");
            paragraphs.add(new Paragraph(title || subtitle ? 0 : headingLevel, title, subtitle, indent, cleaned));
            segments = null;
        }

        private void characters(String text) {
            if (segments == null || text.isEmpty()) {
                return;
            }
            // No ODF, espaços seguidos, quebras de linha e tabs do XML valem um espaço (os de verdade são text:s)
            add(WHITESPACE.matcher(text).replaceAll(" "));
        }

        private void add(String text) {
            if (segments == null || text.isEmpty()) {
                return;
            }
            segments.add(new Segment(text, marks()));
        }

        /** Formatação do trecho: a do estilo do parágrafo, sobreposta pela dos spans abertos, do de fora para dentro. */
        private Set<String> marks() {
            Set<String> marks = new LinkedHashSet<>();
            for (String mark : MARKS) {
                Boolean value = styles.mark("paragraph", paragraphStyle, mark);
                var outerFirst = spans.descendingIterator();
                while (outerFirst.hasNext()) {
                    Boolean span = styles.mark("text", outerFirst.next(), mark);
                    if (span != null) {
                        value = span;
                    }
                }
                if (Boolean.TRUE.equals(value)) {
                    marks.add(mark);
                }
            }
            return Set.copyOf(marks);
        }

        private static String nonNull(String value) {
            return value == null ? "" : value;
        }

        private static boolean positive(String length) {
            Matcher matcher = LENGTH.matcher(length.strip());
            return matcher.find() && Double.parseDouble(matcher.group(1)) > 0;
        }

        private static Integer parseInt(String value) {
            if (value == null) {
                return null;
            }
            try {
                return Integer.parseInt(value.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
