package com.booksmith.manuscript.internal;

import java.util.List;
import java.util.Set;

/**
 * Texto lido de um arquivo importado, igual para todos os formatos (.docx, .odt, .md, .txt): parágrafos com
 * nível de título, recuo e trechos formatados. {@link ChapterSplitter} transforma isso em capítulos.
 */
final class ImportedText {

    private ImportedText() {
    }

    /** Um trecho de texto com a mesma formatação, ou uma quebra de linha ({@code text == null}). */
    record Segment(String text, Set<String> marks) {

        /** Marca interna da nota de rodapé: o trecho é o texto da nota (vira o nó "footnote"). */
        static final String FOOTNOTE = "footnote";

        static Segment lineBreak() {
            return new Segment(null, Set.of());
        }

        static Segment footnote(String text) {
            return new Segment(text, Set.of(FOOTNOTE));
        }

        boolean isLineBreak() {
            return text == null;
        }

        boolean isFootnote() {
            return marks.contains(FOOTNOTE);
        }
    }

    /**
     * {@code part}: título de parte do livro (estilo "Parte" do Word que o Booksmith gera). {@code backMatter}:
     * título de página depois da história (agradecimentos, "sobre o autor", estilo "Pós-textual"); dali em diante
     * não é texto de capítulo.
     */
    /**
     * {@code size}: tamanho da letra em meios-pontos, como o Word guarda (24 = 12 pt); 0 = desconhecido (o
     * tamanho padrão do documento).
     */
    record Paragraph(int headingLevel, boolean title, boolean subtitle, boolean indent, List<Segment> segments,
                     boolean part, boolean backMatter, int size) {
        Paragraph(int headingLevel, boolean title, boolean subtitle, boolean indent, List<Segment> segments) {
            this(headingLevel, title, subtitle, indent, segments, false, false, 0);
        }

        Paragraph(int headingLevel, boolean title, boolean subtitle, boolean indent, List<Segment> segments,
                  boolean part, boolean backMatter) {
            this(headingLevel, title, subtitle, indent, segments, part, backMatter, 0);
        }

        /** O mesmo parágrafo como título de nível {@code level} (0 = texto comum), sem os papéis especiais. */
        Paragraph asHeading(int level) {
            return new Paragraph(level, false, false, level == 0 && indent, segments, false, false, size);
        }

        /** Todo o texto em negrito (um título feito "à mão", sem estilo de título). */
        boolean allBold() {
            boolean any = false;
            for (Segment segment : segments) {
                if (segment.isLineBreak() || segment.isFootnote() || segment.text().isBlank()) {
                    continue;
                }
                if (!segment.marks().contains("bold")) {
                    return false;
                }
                any = true;
            }
            return any;
        }

        static Paragraph plain(boolean indent, String text) {
            return new Paragraph(0, false, false, indent, List.of(new Segment(text, Set.of())));
        }

        String text() {
            StringBuilder text = new StringBuilder();
            for (Segment segment : segments) {
                if (!segment.isFootnote()) { // a nota não é texto do parágrafo
                    text.append(segment.isLineBreak() ? "\n" : segment.text());
                }
            }
            return text.toString();
        }

        boolean isBlank() {
            return text().isBlank();
        }
    }

    /** Texto lido mais o que ficou de fora, para avisar o usuário. */
    record Result(List<Paragraph> paragraphs, int images, int tables, int footnotes) {
    }

    // ===== Limpeza comum aos leitores =====

    /** Junta trechos vizinhos com a mesma formatação. */
    static List<Segment> merge(List<Segment> segments) {
        List<Segment> merged = new java.util.ArrayList<>();
        for (Segment segment : segments) {
            Segment last = merged.isEmpty() ? null : merged.getLast();
            if (last != null && !last.isLineBreak() && !segment.isLineBreak() && !last.isFootnote()
                    && !segment.isFootnote() && last.marks().equals(segment.marks())) {
                merged.set(merged.size() - 1, new Segment(last.text() + segment.text(), last.marks()));
            } else {
                merged.add(segment);
            }
        }
        return merged;
    }

    /** Remove espaços e tabs das pontas e quebras de linha soltas no começo e no fim; tabs internos viram espaço. */
    static List<Segment> trim(List<Segment> segments) {
        List<Segment> result = new java.util.ArrayList<>();
        for (Segment segment : segments) {
            result.add(segment.isLineBreak() ? segment : new Segment(segment.text().replace('\t', ' '), segment.marks()));
        }
        while (!result.isEmpty() && (result.getFirst().isLineBreak() || result.getFirst().text().isBlank())) {
            result.removeFirst();
        }
        while (!result.isEmpty() && (result.getLast().isLineBreak() || result.getLast().text().isBlank())) {
            result.removeLast();
        }
        if (!result.isEmpty()) {
            Segment first = result.getFirst();
            result.set(0, new Segment(first.text().stripLeading(), first.marks()));
            Segment last = result.getLast();
            result.set(result.size() - 1, new Segment(last.text().stripTrailing(), last.marks()));
        }
        return result;
    }

    /** Texto que começa com Tab: o Tab é o recuo do parágrafo, não um caractere. */
    static boolean startsWithTab(List<Segment> segments) {
        return !segments.isEmpty() && !segments.getFirst().isLineBreak() && segments.getFirst().text().startsWith("\t");
    }
}
