package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.internal.ImportedText.Paragraph;
import com.booksmith.manuscript.internal.ImportedText.Segment;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Monta os capítulos com a estrutura que o escritor aprovou na análise ({@link StructureAnalyzer}): cada divisão do
 * texto com o tipo escolhido (parte, capítulo, sem número, subcapítulo, dedicatória, texto, tirar) e, se ele quis,
 * o sumário do arquivo como esqueleto do livro (os capítulos do plano que ainda não têm texto nascem vazios, com
 * os subcapítulos como títulos). Também cria um livro só a partir de um sumário colado ({@link #fromOutline}).
 */
public final class StructureBuilder {

    /** Uma divisão aprovada: o parágrafo, o tipo e o título (o escritor pode ter corrigido). */
    public record Decision(int index, String kind, String title) {
    }

    /** Um item do sumário aprovado; {@code match}: índice da divisão do texto com o mesmo capítulo, ou -1. */
    public record OutlineEntry(String kind, String title, int match) {
    }

    /** {@code dedication}: o texto da dedicatória encontrada (vai para as páginas do livro), ou null. */
    record Result(List<ChapterSplitter.Draft> chapters, String dedication, List<String> warnings) {
    }

    static final Set<String> KINDS = Set.of("part", "chapter", "unnumbered", "section", "dedication", "text", "remove");
    static final Set<String> OUTLINE_KINDS = Set.of("part", "chapter", "unnumbered", "section");
    private static final String OPENING_TITLE = "Abertura";
    private static final int SECTION_LEVEL = 2;

    private StructureBuilder() {
    }

    /** Um capítulo vindo do texto: de onde começou no arquivo e o que tem dentro. */
    private static final class TextChapter {
        final int source;
        String title;
        String partTitle;
        final List<Paragraph> paragraphs = new ArrayList<>();
        final boolean opening;

        TextChapter(int source, String title, String partTitle, boolean opening) {
            this.source = source;
            this.title = title;
            this.partTitle = partTitle;
            this.opening = opening;
        }
    }

    static Result build(StructureAnalyzer.Analysis analysis, List<Decision> decisions, boolean useOutline,
                        List<OutlineEntry> outline, String fallbackTitle, JsonMapper mapper) {
        List<Paragraph> paragraphs = analysis.paragraphs();
        Map<Integer, Decision> byIndex = new HashMap<>();
        for (Decision decision : decisions == null ? List.<Decision>of() : decisions) {
            String kind = decision.kind() == null ? "" : decision.kind().toLowerCase(Locale.ROOT);
            if (!KINDS.contains(kind) || decision.index() < 0 || decision.index() >= paragraphs.size()) {
                throw invalid("Divisão inválida na estrutura aprovada.");
            }
            byIndex.put(decision.index(), new Decision(decision.index(), kind, title(decision.title(), paragraphs.get(decision.index()))));
        }
        StructureAnalyzer.Outline found = analysis.outline();

        // 1. O texto, na ordem do arquivo
        List<TextChapter> chapters = new ArrayList<>();
        TextChapter current = new TextChapter(-1, fallbackTitle == null ? OPENING_TITLE : fallbackTitle, null, true);
        String pendingPart = null;
        StringBuilder dedication = null;
        boolean inDedication = false;
        for (int i = 0; i < paragraphs.size(); i++) {
            if (found != null && i >= found.from() && i <= found.to()) {
                inDedication = false; // o sumário encerra a dedicatória
                continue; // o sumário do arquivo não é texto do livro
            }
            Paragraph paragraph = paragraphs.get(i);
            Decision decision = byIndex.get(i);
            String kind = decision == null ? null : decision.kind();
            if (kind == null || kind.equals("text")) {
                if (inDedication) {
                    dedication.append(dedication.isEmpty() ? "" : "\n").append(paragraph.text().strip());
                } else {
                    current.paragraphs.add(body(paragraph, kind != null));
                }
                continue;
            }
            inDedication = false;
            switch (kind) {
                case "remove" -> {
                    // fica de fora
                }
                case "part" -> pendingPart = decision.title();
                case "chapter", "unnumbered" -> {
                    if (!current.paragraphs.isEmpty() || !current.opening) {
                        chapters.add(current);
                    }
                    current = new TextChapter(i, decision.title(), pendingPart, false);
                    pendingPart = null;
                }
                case "section" -> current.paragraphs.add(heading(decision.title(), paragraph));
                case "dedication" -> {
                    dedication = dedication == null ? new StringBuilder() : dedication;
                    inDedication = true;
                }
                default -> throw invalid("Tipo de divisão desconhecido.");
            }
        }
        if (!current.paragraphs.isEmpty() || !current.opening) {
            chapters.add(current);
        }

        // 2. Com o sumário como esqueleto: a ordem e os capítulos que faltam vêm dele
        List<String> warnings = new ArrayList<>();
        List<TextChapter> ordered = chapters;
        if (useOutline && found != null && outline != null && !outline.isEmpty()) {
            ordered = withOutline(chapters, found.from(), outline, warnings);
        }

        List<ChapterSplitter.Draft> drafts = new ArrayList<>();
        for (TextChapter chapter : ordered) {
            drafts.add(ChapterSplitter.draft(chapter.title, chapter.paragraphs, chapter.partTitle, mapper));
        }
        if (drafts.size() > ChapterSplitter.MAX_CHAPTERS) {
            throw ApiException.unprocessable("import_too_many_chapters",
                    "A estrutura tem %d capítulos; o limite por importação é %d.".formatted(drafts.size(), ChapterSplitter.MAX_CHAPTERS));
        }
        String dedicationText = dedication == null || dedication.isEmpty() ? null : dedication.toString();
        return new Result(drafts, dedicationText, warnings);
    }

    private static List<TextChapter> withOutline(List<TextChapter> chapters, int outlineStart,
                                                 List<OutlineEntry> outline, List<String> warnings) {
        Map<Integer, TextChapter> bySource = new HashMap<>();
        chapters.forEach(c -> bySource.put(c.source, c));
        Set<TextChapter> used = new HashSet<>();
        List<TextChapter> result = new ArrayList<>();
        // O que vem antes do sumário no arquivo (apresentação, dedicatória…) continua na frente
        for (TextChapter chapter : chapters) {
            if (chapter.source < outlineStart) {
                result.add(chapter);
                used.add(chapter);
            }
        }
        String pendingPart = null;
        TextChapter empty = null;
        for (OutlineEntry entry : outline) {
            String kind = entry.kind() == null ? "" : entry.kind().toLowerCase(Locale.ROOT);
            if (!OUTLINE_KINDS.contains(kind)) {
                throw invalid("Item do sumário inválido.");
            }
            String title = cleanTitle(entry.title());
            if (title.isEmpty()) {
                continue;
            }
            switch (kind) {
                case "part" -> {
                    pendingPart = title;
                    empty = null;
                }
                case "section" -> {
                    if (empty != null) {
                        empty.paragraphs.add(heading(title, null)); // subcapítulo planejado, ainda sem texto
                    }
                }
                default -> {
                    TextChapter text = entry.match() >= 0 ? bySource.get(entry.match()) : null;
                    if (text != null && !used.contains(text)) {
                        text.title = title;
                        if (pendingPart != null) {
                            text.partTitle = pendingPart;
                        }
                        result.add(text);
                        used.add(text);
                        empty = null;
                    } else {
                        empty = new TextChapter(-1, title, pendingPart, false);
                        result.add(empty);
                    }
                    pendingPart = null;
                }
            }
        }
        for (TextChapter chapter : chapters) {
            if (!used.contains(chapter)) {
                result.add(chapter);
                warnings.add("\"%s\" não está no sumário: foi para o fim do livro.".formatted(chapter.title));
            }
        }
        return result;
    }

    /** Livro novo a partir de um sumário colado: partes, capítulos vazios e os subcapítulos como títulos. */
    static Result fromOutline(List<OutlineEntry> outline, JsonMapper mapper) {
        if (outline == null || outline.isEmpty()) {
            throw invalid("Escreva o sumário: uma linha por capítulo.");
        }
        List<TextChapter> chapters = withOutline(List.of(), Integer.MAX_VALUE, outline.stream()
                .map(e -> new OutlineEntry(e.kind(), e.title(), -1)).toList(), new ArrayList<>());
        if (chapters.isEmpty()) {
            throw invalid("O sumário não tem nenhum capítulo.");
        }
        List<ChapterSplitter.Draft> drafts = new ArrayList<>();
        for (TextChapter chapter : chapters) {
            drafts.add(ChapterSplitter.draft(chapter.title, chapter.paragraphs, chapter.partTitle, mapper));
        }
        if (drafts.size() > ChapterSplitter.MAX_CHAPTERS) {
            throw ApiException.unprocessable("import_too_many_chapters",
                    "O sumário tem %d capítulos; o limite é %d.".formatted(drafts.size(), ChapterSplitter.MAX_CHAPTERS));
        }
        return new Result(drafts, null, List.of());
    }

    /** Texto comum: um parágrafo que parecia título e o escritor disse que é texto volta a ser parágrafo. */
    private static Paragraph body(Paragraph paragraph, boolean demoted) {
        if (demoted || paragraph.title() || paragraph.subtitle()) {
            return paragraph.asHeading(0);
        }
        return paragraph.headingLevel() > 0 ? paragraph.asHeading(Math.clamp(paragraph.headingLevel(), 2, 3)) : paragraph;
    }

    /** Subcapítulo: título dentro do capítulo, com o texto que o escritor aprovou. */
    private static Paragraph heading(String title, Paragraph original) {
        List<Segment> segments = original != null && original.text().strip().equals(title)
                ? original.segments().stream().map(s -> s.isLineBreak() ? s : new Segment(s.text(), Set.of())).toList()
                : List.of(new Segment(title, Set.of()));
        return new Paragraph(SECTION_LEVEL, false, false, false, segments);
    }

    private static String title(String requested, Paragraph paragraph) {
        String title = cleanTitle(requested);
        return title.isEmpty() ? cleanTitle(paragraph.text()) : title;
    }

    private static String cleanTitle(String title) {
        String t = title == null ? "" : title.replace('\n', ' ').replaceAll("\\s+", " ").strip();
        return t.length() > 200 ? t.substring(0, 200).strip() : t;
    }

    private static ApiException invalid(String message) {
        return ApiException.unprocessable("import_structure_invalid", message);
    }
}
