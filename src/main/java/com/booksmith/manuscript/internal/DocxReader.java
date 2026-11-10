package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.internal.ImportedText.Paragraph;
import com.booksmith.manuscript.internal.ImportedText.Result;
import com.booksmith.manuscript.internal.ImportedText.Segment;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê o texto de um arquivo .docx (WordprocessingML) sem bibliotecas de Office: o arquivo é um ZIP com
 * {@code word/document.xml} (o texto) e {@code word/styles.xml} (os estilos, para reconhecer títulos).
 * <p>
 * Guarda o que o editor do Booksmith representa: parágrafos, títulos, recuo da primeira linha, quebras de
 * linha, negrito, itálico, sublinhado e tachado. Imagens, caixas de texto, notas de rodapé e comentários
 * ficam de fora (contados para avisar o usuário). Tabelas viram parágrafos.
 * <p>
 * Proteções em {@link ImportFiles}.
 */
final class DocxReader {

    static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String MC = "http://schemas.openxmlformats.org/markup-compatibility/2006";

    private static final Pattern HEADING_NAME = Pattern.compile("heading ([1-9])");

    /** Elementos cujo conteúdo não é texto do livro. */
    private static final Set<String> SKIPPED = Set.of(
            "del", "moveFrom", "sdtPr", "footnoteReference", "endnoteReference", "commentReference",
            "instrText", "delText", "rPrChange", "pPrChange");
    private static final Set<String> MEDIA = Set.of("drawing", "pict", "object");

    private DocxReader() {
    }

    static Result read(byte[] file) {
        Map<String, byte[]> parts = ImportFiles.unzip(file, Set.of("word/document.xml", "word/styles.xml"),
                DocxReader::notDocx);
        byte[] document = parts.get("word/document.xml");
        if (document == null) {
            throw notDocx();
        }
        Styles styles = parts.containsKey("word/styles.xml") ? Styles.parse(parts.get("word/styles.xml")) : Styles.EMPTY;
        return new DocumentParser(styles).parse(document);
    }

    private static String attr(XMLStreamReader reader, String name) {
        String value = reader.getAttributeValue(W, name);
        return value != null ? value : reader.getAttributeValue(null, name);
    }

    /** Propriedades liga/desliga do Word: {@code <w:b/>} liga; {@code w:val="0|false|off"} desliga. */
    private static boolean on(XMLStreamReader reader) {
        String value = attr(reader, "val");
        return value == null || !(value.equals("0") || value.equalsIgnoreCase("false") || value.equalsIgnoreCase("off"));
    }

    static ApiException notDocx() {
        return ApiException.unprocessable("docx_invalid",
                "Não conseguimos ler este arquivo. Ele precisa ser um documento do Word (.docx). "
                        + "No Word ou no Google Docs, use \"Salvar como\" ou \"Fazer download\" em .docx.");
    }

    // ===== Estilos =====

    /** O que importa de cada estilo: nível de título e formatação de estilos de caractere. */
    private record Style(int headingLevel, boolean title, boolean subtitle, Set<String> marks, String basedOn,
                         boolean part) {
    }

    private record Styles(Map<String, Style> byId) {

        static final Styles EMPTY = new Styles(Map.of());

        static Styles parse(byte[] bytes) {
            Map<String, Style> styles = new HashMap<>();
            try {
                XMLStreamReader reader = ImportFiles.xml(bytes);
                String id = null;
                String name = null;
                String basedOn = null;
                Integer outline = null;
                Set<String> marks = new LinkedHashSet<>();
                boolean inRunProps = false;
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT && W.equals(reader.getNamespaceURI())) {
                        switch (reader.getLocalName()) {
                            case "style" -> {
                                id = attr(reader, "styleId");
                                name = null;
                                basedOn = null;
                                outline = null;
                                marks = new LinkedHashSet<>();
                            }
                            case "name" -> name = attr(reader, "val");
                            case "basedOn" -> basedOn = attr(reader, "val");
                            case "outlineLvl" -> outline = parseInt(attr(reader, "val"));
                            case "rPr" -> inRunProps = true;
                            default -> {
                                if (inRunProps) {
                                    Mark.of(reader).ifPresent(marks::add);
                                }
                            }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT && W.equals(reader.getNamespaceURI())) {
                        if (reader.getLocalName().equals("rPr")) {
                            inRunProps = false;
                        } else if (reader.getLocalName().equals("style") && id != null) {
                            styles.put(id, toStyle(name, outline, marks, basedOn));
                            id = null;
                        }
                    }
                }
            } catch (XMLStreamException e) {
                return EMPTY; // estilos ilegíveis: importa o texto mesmo assim, sem reconhecer títulos
            }
            return new Styles(styles);
        }

        private static Style toStyle(String name, Integer outline, Set<String> marks, String basedOn) {
            String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT).trim();
            int level = 0;
            Matcher heading = HEADING_NAME.matcher(normalized);
            if (heading.matches()) {
                level = Integer.parseInt(heading.group(1));
            } else if (outline != null && outline >= 0 && outline < 9) {
                level = outline + 1;
            }
            return new Style(level, normalized.equals("title"), normalized.equals("subtitle"), Set.copyOf(marks), basedOn,
                    normalized.equals("parte") || normalized.equals("part"));
        }

        /** Nível de título do estilo, herdado do estilo base quando o próprio não define. */
        int headingLevel(String styleId) {
            for (int hops = 0; styleId != null && hops < 10; hops++) {
                Style style = byId.get(styleId);
                if (style == null) {
                    return 0;
                }
                if (style.headingLevel() > 0) {
                    return style.headingLevel();
                }
                styleId = style.basedOn();
            }
            return 0;
        }

        boolean isSubtitle(String styleId) {
            Style style = styleId == null ? null : byId.get(styleId);
            return style != null && style.subtitle();
        }

        boolean isPart(String styleId) {
            Style style = styleId == null ? null : byId.get(styleId);
            return style != null && style.part();
        }

        boolean isTitle(String styleId) {
            Style style = styleId == null ? null : byId.get(styleId);
            return style != null && style.title();
        }

        Set<String> marks(String styleId) {
            Style style = styleId == null ? null : byId.get(styleId);
            return style == null ? Set.of() : style.marks();
        }
    }

    private static Integer parseInt(String value) {
        try {
            return value == null ? null : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ===== Formatação =====

    private enum Mark {
        BOLD("bold"), ITALIC("italic"), UNDERLINE("underline"), STRIKE("strike");

        final String type;

        Mark(String type) {
            this.type = type;
        }

        /** Formatação ligada pelo elemento atual de um {@code rPr}, se for uma das que o editor tem. */
        static java.util.Optional<String> of(XMLStreamReader reader) {
            Mark mark = switch (reader.getLocalName()) {
                case "b" -> BOLD;
                case "i" -> ITALIC;
                case "u" -> "none".equals(attr(reader, "val")) ? null : UNDERLINE;
                case "strike", "dstrike" -> STRIKE;
                default -> null;
            };
            if (mark == null || (!"u".equals(reader.getLocalName()) && !on(reader))) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(mark.type);
        }
    }

    // ===== Documento =====

    private static final class DocumentParser {

        private final Styles styles;
        private final List<Paragraph> paragraphs = new ArrayList<>();
        private int images;
        private int tables;
        private int footnotes;

        // Parágrafo atual
        private String paragraphStyle;
        private Integer outlineLevel;
        private boolean firstLineIndent;
        private List<Segment> segments;
        private int paragraphDepth;

        // Trecho atual
        private Set<String> runMarks;
        private String runStyle;
        private boolean inRunProps;
        private boolean inParagraphProps;

        DocumentParser(Styles styles) {
            this.styles = styles;
        }

        Result parse(byte[] bytes) {
            try {
                XMLStreamReader reader = ImportFiles.xml(bytes);
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        start(reader);
                    } else if (event == XMLStreamConstants.END_ELEMENT && W.equals(reader.getNamespaceURI())) {
                        end(reader.getLocalName());
                    }
                }
            } catch (XMLStreamException e) {
                throw notDocx().causedBy(e);
            }
            return new Result(List.copyOf(paragraphs), images, tables, footnotes);
        }

        private void start(XMLStreamReader reader) throws XMLStreamException {
            String ns = reader.getNamespaceURI();
            String name = reader.getLocalName();

            // Conteúdo alternativo (formas, caixas de texto): repete o texto em versões diferentes
            if (MC.equals(ns) && name.equals("AlternateContent")) {
                images++;
                ImportFiles.skipElement(reader);
                return;
            }
            if (!W.equals(ns)) {
                return;
            }
            if (MEDIA.contains(name)) {
                images++;
                ImportFiles.skipElement(reader);
                return;
            }
            if (name.equals("footnoteReference") || name.equals("endnoteReference")) {
                footnotes++;
            }
            if (SKIPPED.contains(name)) {
                ImportFiles.skipElement(reader);
                return;
            }

            switch (name) {
                case "tbl" -> tables++;
                case "p" -> startParagraph();
                case "pPr" -> inParagraphProps = true;
                case "pStyle" -> paragraphStyle = attr(reader, "val");
                case "outlineLvl" -> {
                    if (inParagraphProps) {
                        outlineLevel = parseInt(attr(reader, "val"));
                    }
                }
                case "ind" -> {
                    if (inParagraphProps && !inRunProps) {
                        firstLineIndent = positive(attr(reader, "firstLine")) || positive(attr(reader, "firstLineChars"));
                    }
                }
                case "r" -> {
                    runMarks = new LinkedHashSet<>();
                    runStyle = null;
                }
                case "rPr" -> inRunProps = true;
                case "rStyle" -> runStyle = attr(reader, "val");
                case "t" -> text(reader.getElementText());
                case "tab" -> {
                    if (!inParagraphProps) {
                        text("\t");
                    }
                }
                case "br" -> {
                    // Quebra de página separa trechos; quebra de linha vira quebra dentro do parágrafo
                    if ("page".equals(attr(reader, "type")) || "column".equals(attr(reader, "type"))) {
                        return;
                    }
                    if (segments != null) {
                        segments.add(Segment.lineBreak());
                    }
                }
                case "cr" -> {
                    if (segments != null) {
                        segments.add(Segment.lineBreak());
                    }
                }
                case "noBreakHyphen" -> text("-");
                default -> {
                    if (inRunProps && !inParagraphProps && runMarks != null) {
                        Mark.of(reader).ifPresent(runMarks::add);
                    }
                }
            }
        }

        private void end(String name) {
            switch (name) {
                case "p" -> endParagraph();
                case "pPr" -> inParagraphProps = false;
                case "rPr" -> inRunProps = false;
                case "r" -> {
                    runMarks = null;
                    runStyle = null;
                }
                default -> {
                    // nada a fazer
                }
            }
        }

        private void startParagraph() {
            paragraphDepth++;
            if (paragraphDepth > 1) {
                return; // parágrafo dentro de parágrafo (caixa de texto antiga): o texto entra no de fora
            }
            paragraphStyle = null;
            outlineLevel = null;
            firstLineIndent = false;
            segments = new ArrayList<>();
        }

        private void endParagraph() {
            paragraphDepth--;
            if (paragraphDepth > 0 || segments == null) {
                return;
            }
            int level = styles.headingLevel(paragraphStyle);
            if (level == 0 && outlineLevel != null && outlineLevel >= 0 && outlineLevel < 9) {
                level = outlineLevel + 1;
            }
            List<Segment> cleaned = ImportedText.merge(segments);
            // Recuo feito com Tab no começo do parágrafo: vira o recuo do editor, não um caractere
            boolean indent = firstLineIndent || ImportedText.startsWithTab(cleaned);
            cleaned = ImportedText.trim(cleaned);
            paragraphs.add(new Paragraph(level, styles.isTitle(paragraphStyle), styles.isSubtitle(paragraphStyle), indent,
                    cleaned, styles.isPart(paragraphStyle)));
            segments = null;
        }

        private void text(String value) {
            if (segments == null || value == null || value.isEmpty()) {
                return;
            }
            Set<String> marks = new LinkedHashSet<>(styles.marks(runStyle));
            if (runMarks != null) {
                marks.addAll(runMarks);
            }
            segments.add(new Segment(value, Set.copyOf(marks)));
        }

        private static boolean positive(String value) {
            Integer number = parseInt(value);
            return number != null && number > 0;
        }
    }
}
