package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.internal.ImportedText.Paragraph;
import com.booksmith.manuscript.internal.ImportedText.Segment;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Transforma os parágrafos de um arquivo importado (.docx, .odt, .md, .txt) em capítulos do Booksmith
 * (documentos ProseMirror validados).
 * <p>
 * Onde dividir, em ordem de preferência:
 * <ol>
 *   <li>{@link SplitRule#HEADINGS}: nos títulos (estilos "Título 1/2/3" do Word e do LibreOffice, {@code #} do
 *   Markdown), no nível mais alto usado;</li>
 *   <li>{@link SplitRule#CHAPTER_TITLES}: em parágrafos curtos como "Capítulo 3", "Prólogo" ou "Epílogo",
 *   para quem escreveu o título à mão, sem estilo (precisa aparecer ao menos duas vezes);</li>
 *   <li>{@link SplitRule#SINGLE}: tudo num capítulo só, também quando o usuário pede.</li>
 * </ol>
 */
final class ChapterSplitter {

    static final int MAX_CHAPTERS = 300;
    private static final int MAX_TITLE = 200;
    private static final int EXCERPT_LENGTH = 160;
    private static final String OPENING_TITLE = "Abertura";

    /** Parágrafo feito só de símbolos ("* * *", "#", "~~~", "⁂"): quebra de cena. */
    private static final Pattern SCENE_BREAK = Pattern.compile("^\\s*(?:[*#~•·⁂§◇◆✦○●=_—–-]\\s*){1,7}$");
    private static final Pattern CHAPTER_TITLE = Pattern.compile(
            "^(?:cap[ií]tulo|cap\\.|chapter|pr[oó]logo|prologue|ep[ií]logo|epilogue|interl[uú]dio|interlude)"
                    + "(?:\\s.*|[:.—–-].*)?$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final int MAX_CHAPTER_TITLE_LENGTH = 80;

    enum Mode { AUTO, SINGLE }

    enum SplitRule { HEADINGS, CHAPTER_TITLES, SINGLE }

    record Draft(String title, ManuscriptDocument document, String excerpt, String partTitle) {
    }

    record Plan(SplitRule rule, String documentTitle, List<Draft> chapters, List<String> warnings) {

        int totalWords() {
            return chapters.stream().mapToInt(d -> d.document().wordCount()).sum();
        }
    }

    private ChapterSplitter() {
    }

    static Plan plan(ImportedText.Result docx, Mode mode, String fileName, JsonMapper mapper) {
        List<Paragraph> paragraphs = new ArrayList<>(docx.paragraphs().stream().filter(p -> !p.isBlank()).toList());

        // Um parágrafo no estilo "Título" (Title) antes de qualquer texto é o nome do livro, não um capítulo
        String documentTitle = null;
        if (!paragraphs.isEmpty() && paragraphs.getFirst().title()) {
            documentTitle = limit(paragraphs.removeFirst().text().strip());
            // "Subtítulo" logo abaixo do título (subtítulo, nome do autor) também é capa, não texto do livro
            while (!paragraphs.isEmpty() && paragraphs.getFirst().subtitle()) {
                paragraphs.removeFirst();
            }
        }

        dropBackMatter(paragraphs);

        SplitRule rule = SplitRule.SINGLE;
        int chapterLevel = 0;
        if (mode == Mode.AUTO) {
            chapterLevel = paragraphs.stream().mapToInt(Paragraph::headingLevel).filter(l -> l > 0).min().orElse(0);
            if (chapterLevel > 0) {
                rule = SplitRule.HEADINGS;
            } else if (paragraphs.stream().filter(ChapterSplitter::looksLikeChapterTitle).count() >= 2) {
                rule = SplitRule.CHAPTER_TITLES;
            }
        }

        List<Section> sections = split(paragraphs, rule, chapterLevel, fallbackTitle(fileName, documentTitle));
        if (sections.size() > MAX_CHAPTERS) {
            throw ApiException.unprocessable("import_too_many_chapters",
                    "O arquivo tem %d capítulos; o limite por importação é %d. Divida o arquivo em partes."
                            .formatted(sections.size(), MAX_CHAPTERS));
        }

        List<Draft> drafts = new ArrayList<>();
        for (Section section : sections) {
            drafts.add(toDraft(section, rule == SplitRule.HEADINGS ? chapterLevel : 0, mapper));
        }
        return new Plan(rule, documentTitle, drafts, warnings(docx));
    }

    /** Agradecimentos e "sobre o autor" que o Booksmith pôs no fim do Word: dali em diante não é capítulo. */
    private static void dropBackMatter(List<Paragraph> paragraphs) {
        for (int i = 0; i < paragraphs.size(); i++) {
            if (paragraphs.get(i).backMatter()) {
                paragraphs.subList(i, paragraphs.size()).clear();
                return;
            }
        }
    }

    // ===== Divisão em capítulos =====

    private static final class Section {
        final String title;
        /** Texto antes do primeiro título de capítulo: só vira capítulo se tiver conteúdo. */
        final boolean opening;
        /** A parte que este capítulo abre (título com o estilo "Parte" logo antes dele). */
        String partTitle;
        final List<Paragraph> paragraphs = new ArrayList<>();

        Section(String title, boolean opening) {
            this.title = title;
            this.opening = opening;
        }
    }

    private static List<Section> split(List<Paragraph> paragraphs, SplitRule rule, int chapterLevel, String fallbackTitle) {
        List<Section> sections = new ArrayList<>();
        Section current = rule == SplitRule.SINGLE
                ? new Section(fallbackTitle, false)
                : new Section(OPENING_TITLE, true);
        String pendingPart = null;
        for (Paragraph paragraph : paragraphs) {
            if (paragraph.part()) {
                pendingPart = limit(paragraph.text().replace('\n', ' ').strip());
                continue; // não é texto do capítulo: vira a parte do próximo
            }
            boolean boundary = switch (rule) {
                case HEADINGS -> paragraph.headingLevel() == chapterLevel;
                case CHAPTER_TITLES -> looksLikeChapterTitle(paragraph);
                case SINGLE -> false;
            };
            if (boundary) {
                if (!current.paragraphs.isEmpty() || !current.opening) {
                    sections.add(current);
                }
                current = new Section(limit(paragraph.text().replace('\n', ' ').strip()), false);
                current.partTitle = pendingPart;
                pendingPart = null;
            } else {
                current.paragraphs.add(paragraph);
            }
        }
        if (!current.paragraphs.isEmpty() || !current.opening || sections.isEmpty()) {
            sections.add(current);
        }
        return sections;
    }

    private static boolean looksLikeChapterTitle(Paragraph paragraph) {
        String text = paragraph.text().strip();
        return text.length() <= MAX_CHAPTER_TITLE_LENGTH && !text.contains("\n") && CHAPTER_TITLE.matcher(text).matches();
    }

    // ===== Documento ProseMirror =====

    private static Draft toDraft(Section section, int chapterLevel, JsonMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "doc");
        root.putObject("attrs").put("schemaVersion", ManuscriptDocument.CURRENT_SCHEMA_VERSION);
        ArrayNode content = root.putArray("content");

        StringBuilder excerpt = new StringBuilder();
        boolean lastWasBreak = true; // não começa capítulo com quebra de cena
        for (Paragraph paragraph : section.paragraphs) {
            String text = paragraph.text().strip();
            if (paragraph.headingLevel() == 0 && !paragraph.title() && SCENE_BREAK.matcher(text).matches()) {
                if (!lastWasBreak) {
                    block(content, "sceneBreak");
                    lastWasBreak = true;
                }
                continue;
            }
            lastWasBreak = false;

            ObjectNode block;
            int level = paragraph.title() ? 1 : paragraph.headingLevel();
            if (level > 0) {
                block = block(content, "heading");
                int relative = chapterLevel > 0 ? level - chapterLevel + 1 : level;
                ((ObjectNode) block.get("attrs")).put("level", Math.clamp(relative, 1, 3));
            } else {
                block = block(content, "paragraph");
                if (paragraph.indent()) {
                    ((ObjectNode) block.get("attrs")).put("indent", true);
                }
                if (excerpt.length() < EXCERPT_LENGTH) {
                    excerpt.append(excerpt.isEmpty() ? "" : " ").append(text.replace('\n', ' '));
                }
            }
            inline(block.putArray("content"), paragraph.segments());
        }
        // Quebra de cena no fim do capítulo não separa nada
        if (!content.isEmpty() && "sceneBreak".equals(content.get(content.size() - 1).get("type").stringValue())) {
            content.remove(content.size() - 1);
        }
        if (content.isEmpty()) {
            block(content, "paragraph");
        }

        ManuscriptDocument document;
        try {
            document = ManuscriptDocument.parse(root, mapper);
        } catch (ApiException e) {
            if ("document_too_large".equals(e.getCode())) {
                throw ApiException.unprocessable("import_chapter_too_large",
                        "O capítulo \"%s\" é grande demais para um capítulo só. Separe-o com títulos e importe de novo."
                                .formatted(section.title)).causedBy(e);
            }
            throw e;
        }
        return new Draft(section.title, document, shorten(excerpt.toString()), section.partTitle);
    }

    private static ObjectNode block(ArrayNode content, String type) {
        ObjectNode block = content.addObject();
        block.put("type", type);
        block.putObject("attrs").put("id", ManuscriptDocument.newBlockId());
        return block;
    }

    private static void inline(ArrayNode content, List<Segment> segments) {
        for (Segment segment : segments) {
            ObjectNode node = content.addObject();
            if (segment.isLineBreak()) {
                node.put("type", "hardBreak");
                continue;
            }
            node.put("type", "text");
            node.put("text", segment.text());
            if (!segment.marks().isEmpty()) {
                ArrayNode marks = node.putArray("marks");
                // Ordem estável, a mesma do editor
                for (String mark : List.of("bold", "italic", "underline", "strike")) {
                    if (segment.marks().contains(mark)) {
                        marks.addObject().put("type", mark);
                    }
                }
            }
        }
    }

    // ===== Textos =====

    private static List<String> warnings(ImportedText.Result docx) {
        List<String> warnings = new ArrayList<>();
        if (docx.images() > 0) {
            warnings.add(docx.images() == 1
                    ? "1 imagem ou caixa de texto ficou de fora: o Booksmith ainda não importa imagens."
                    : "%d imagens ou caixas de texto ficaram de fora: o Booksmith ainda não importa imagens."
                    .formatted(docx.images()));
        }
        if (docx.footnotes() > 0) {
            warnings.add(docx.footnotes() == 1
                    ? "1 nota de rodapé ficou de fora."
                    : "%d notas de rodapé ficaram de fora.".formatted(docx.footnotes()));
        }
        if (docx.tables() > 0) {
            warnings.add(docx.tables() == 1
                    ? "1 tabela virou parágrafos de texto."
                    : "%d tabelas viraram parágrafos de texto.".formatted(docx.tables()));
        }
        return warnings;
    }

    private static String fallbackTitle(String fileName, String documentTitle) {
        if (documentTitle != null && !documentTitle.isBlank()) {
            return documentTitle;
        }
        String name = fileName == null ? "" : fileName.replaceAll("(?i)\\.(docx|odt|txt|md|markdown)$", "").replace('_', ' ').strip();
        return name.isBlank() ? "Capítulo importado" : limit(name);
    }

    private static String limit(String title) {
        return title.length() > MAX_TITLE ? title.substring(0, MAX_TITLE).strip() : title;
    }

    private static String shorten(String text) {
        if (text.length() <= EXCERPT_LENGTH) {
            return text;
        }
        int cut = text.lastIndexOf(' ', EXCERPT_LENGTH);
        return text.substring(0, cut > 40 ? cut : EXCERPT_LENGTH).strip() + "…";
    }
}
