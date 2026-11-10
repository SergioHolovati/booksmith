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

        static Segment lineBreak() {
            return new Segment(null, Set.of());
        }

        boolean isLineBreak() {
            return text == null;
        }
    }

    /** {@code part}: título de parte do livro (estilo "Parte" do Word que o Booksmith gera). */
    record Paragraph(int headingLevel, boolean title, boolean subtitle, boolean indent, List<Segment> segments,
                     boolean part) {

        Paragraph(int headingLevel, boolean title, boolean subtitle, boolean indent, List<Segment> segments) {
            this(headingLevel, title, subtitle, indent, segments, false);
        }

        static Paragraph plain(boolean indent, String text) {
            return new Paragraph(0, false, false, indent, List.of(new Segment(text, Set.of())));
        }

        String text() {
            StringBuilder text = new StringBuilder();
            for (Segment segment : segments) {
                text.append(segment.isLineBreak() ? "\n" : segment.text());
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
            if (last != null && !last.isLineBreak() && !segment.isLineBreak() && last.marks().equals(segment.marks())) {
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
