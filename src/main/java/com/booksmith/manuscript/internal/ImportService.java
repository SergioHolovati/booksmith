package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.events.ChapterSaved;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Importação de arquivos (.docx, .odt, .md, .txt) para um livro: primeiro a análise da estrutura (partes,
 * capítulos, páginas sem número, subcapítulos e o sumário do arquivo), que o escritor revisa e aprova; depois a
 * importação com a estrutura aprovada, que acrescenta os capítulos no fim do livro. Nada do que já existe é
 * alterado. Também cria capítulos vazios a partir de um sumário colado ("Criar pelo sumário").
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportService {

    private final BookService bookService;
    private final ChapterRepository chapterRepository;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ImportPlan preview(UUID ownerId, UUID bookId, byte[] file, String fileName, boolean single) {
        bookService.require(ownerId, bookId);
        return ImportPlan.of(plan(file, fileName, single), List.of());
    }

    @Transactional
    public ImportPlan importFile(UUID ownerId, UUID bookId, byte[] file, String fileName, boolean single) {
        Book book = bookService.require(ownerId, bookId);
        ChapterSplitter.Plan plan = plan(file, fileName, single);
        List<Chapter> created = createChapters(book, ownerId, plan.chapters());
        log.info("Importação de arquivo: {} capítulos, {} palavras no livro {}", created.size(), plan.totalWords(), bookId);
        return ImportPlan.of(plan, created.stream().map(Chapter::getId).toList());
    }

    // ===== Análise e estrutura aprovada =====

    /** A estrutura proposta, para o escritor revisar. Não grava nada. */
    @Transactional(readOnly = true)
    public StructureView analyze(UUID ownerId, UUID bookId, byte[] file, String fileName) {
        bookService.require(ownerId, bookId);
        ImportedText.Result text = read(file, fileName);
        StructureAnalyzer.Analysis analysis = StructureAnalyzer.analyze(text);
        return StructureView.of(analysis, ChapterSplitter.warnings(text));
    }

    /**
     * Importa com a estrutura que o escritor aprovou: o arquivo é lido de novo (nada fica guardado entre a análise e a
     * importação) e as divisões apontam para os mesmos parágrafos. A dedicatória encontrada vai para as páginas do
     * livro, se ele ainda não tem uma.
     */
    @Transactional
    public ImportPlan importStructured(UUID ownerId, UUID bookId, byte[] file, String fileName, StructureRequest request) {
        Book book = bookService.require(ownerId, bookId);
        ImportedText.Result text = read(file, fileName);
        StructureAnalyzer.Analysis analysis = StructureAnalyzer.analyze(text);
        StructureBuilder.Result result = StructureBuilder.build(analysis, request.divisions(), request.useOutline(),
                request.outline(), analysis.documentTitle(), jsonMapper);
        if (result.dedication() != null && (book.getDedication() == null || book.getDedication().isBlank())) {
            book.setDedication(result.dedication().length() > 2_000 ? result.dedication().substring(0, 2_000) : result.dedication());
        }
        List<String> warnings = new ArrayList<>(ChapterSplitter.warnings(text));
        warnings.addAll(result.warnings());
        return save(book, ownerId, result.chapters(), "STRUCTURED", analysis.documentTitle(), warnings);
    }

    /** Lê um sumário colado: a estrutura proposta, para revisar antes de criar. */
    public List<OutlineItemView> previewOutline(UUID ownerId, UUID bookId, String text) {
        bookService.require(ownerId, bookId);
        if (text == null || text.isBlank()) {
            throw ApiException.unprocessable("outline_empty", "Escreva o sumário: uma linha por capítulo.");
        }
        if (text.length() > 40_000) {
            throw ApiException.unprocessable("outline_too_long", "O sumário passa de 40 mil caracteres.");
        }
        return OutlineParser.parse(text).stream()
                .map(i -> new OutlineItemView(i.kind().name().toLowerCase(java.util.Locale.ROOT), i.title(), -1))
                .toList();
    }

    /** Cria os capítulos (vazios, com os subcapítulos como títulos) a partir do sumário aprovado. */
    @Transactional
    public ImportPlan createFromOutline(UUID ownerId, UUID bookId, List<StructureBuilder.OutlineEntry> outline) {
        Book book = bookService.require(ownerId, bookId);
        StructureBuilder.Result result = StructureBuilder.fromOutline(outline, jsonMapper);
        return save(book, ownerId, result.chapters(), "OUTLINE", null, List.of());
    }

    /** Cria os capítulos no fim do livro, na ordem, e avisa como o autosave (o agente de continuidade lê). */
    private List<Chapter> createChapters(Book book, UUID ownerId, List<ChapterSplitter.Draft> drafts) {
        UUID bookId = book.getId();
        int position = chapterRepository.maxPosition(bookId);
        List<Chapter> created = new ArrayList<>();
        for (ChapterSplitter.Draft draft : drafts) {
            Chapter chapter = Chapter.builder()
                    .bookId(bookId)
                    .position(++position)
                    .title(draft.title())
                    .partTitle(draft.partTitle())
                    .build();
            chapter.applyDocument(draft.document());
            created.add(chapterRepository.save(chapter));
        }
        chapterRepository.flush();
        book.setUpdatedAt(Instant.now(clock));
        // Mesmo evento do autosave: o agente de continuidade vai ler os capítulos importados como os escritos
        for (Chapter chapter : created) {
            events.publishEvent(new ChapterSaved(chapter.getId(), bookId, ownerId, chapter.getVersion(),
                    chapter.getContentHash(), Instant.now(clock)));
        }
        return created;
    }

    private ImportPlan save(Book book, UUID ownerId, List<ChapterSplitter.Draft> drafts, String rule, String documentTitle,
                            List<String> warnings) {
        UUID bookId = book.getId();
        List<Chapter> created = createChapters(book, ownerId, drafts);
        int words = drafts.stream().mapToInt(d -> d.document().wordCount()).sum();
        log.info("Importação ({}): {} capítulos, {} palavras no livro {}", rule, created.size(), words, bookId);
        List<ImportedChapter> chapters = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            ChapterSplitter.Draft draft = drafts.get(i);
            chapters.add(new ImportedChapter(created.get(i).getId(), draft.title(), draft.document().wordCount(), draft.excerpt()));
        }
        return new ImportPlan(rule, documentTitle, words, List.copyOf(warnings), chapters);
    }

    private static ImportedText.Result read(byte[] file, String fileName) {
        if (file == null || file.length == 0) {
            throw ApiException.unprocessable("import_empty", "O arquivo está vazio. Escolha outro para importar.");
        }
        return ImportFormat.read(file, fileName);
    }

    /** A estrutura aprovada: as divisões (parágrafo, tipo, título) e, opcionalmente, o sumário como esqueleto. */
    public record StructureRequest(List<StructureBuilder.Decision> divisions, boolean useOutline,
                                   List<StructureBuilder.OutlineEntry> outline) {
    }

    /** Item do sumário: {@code kind} part, chapter, unnumbered ou section; {@code match} a divisão do texto, ou -1. */
    public record OutlineItemView(String kind, String title, int match) {
    }

    public record DivisionView(int index, String kind, String title, String confidence, List<String> reasons, int words,
                               String excerpt, boolean inOutline) {
    }

    /** {@code withText}: quantos itens do sumário já têm texto no arquivo. */
    public record OutlineView(boolean titled, List<OutlineItemView> items, int withText) {
    }

    public record StructureView(String documentTitle, int totalWords, List<DivisionView> divisions, OutlineView outline,
                                List<String> warnings) {

        static StructureView of(StructureAnalyzer.Analysis analysis, List<String> warnings) {
            List<DivisionView> divisions = analysis.divisions().stream()
                    .map(d -> new DivisionView(d.index(), d.kind().name().toLowerCase(java.util.Locale.ROOT), d.title(),
                            d.confidence().name().toLowerCase(java.util.Locale.ROOT), d.reasons(), d.words(), d.excerpt(),
                            d.inOutline()))
                    .toList();
            OutlineView outline = null;
            StructureAnalyzer.Outline found = analysis.outline();
            if (found != null) {
                List<OutlineItemView> items = new ArrayList<>();
                for (int i = 0; i < found.items().size(); i++) {
                    OutlineParser.Item item = found.items().get(i);
                    items.add(new OutlineItemView(item.kind().name().toLowerCase(java.util.Locale.ROOT), item.title(),
                            found.matches().get(i)));
                }
                outline = new OutlineView(found.titled(), items, found.withText());
            }
            int total = 0;
            for (int i = 0; i < analysis.paragraphs().size(); i++) {
                if (found == null || i < found.from() || i > found.to()) {
                    total += StructureAnalyzer.wordCount(analysis.paragraphs().get(i).text());
                }
            }
            return new StructureView(analysis.documentTitle(), total, divisions, outline, List.copyOf(warnings));
        }
    }

    private ChapterSplitter.Plan plan(byte[] file, String fileName, boolean single) {
        if (file == null || file.length == 0) {
            throw ApiException.unprocessable("import_empty", "O arquivo está vazio. Escolha outro para importar.");
        }
        ImportedText.Result text = ImportFormat.read(file, fileName);
        return ChapterSplitter.plan(text, single ? ChapterSplitter.Mode.SINGLE : ChapterSplitter.Mode.AUTO, fileName, jsonMapper);
    }

    /** Prévia ou resultado da importação (com {@code id} dos capítulos criados). */
    public record ImportPlan(String splitRule, String documentTitle, int totalWords, List<String> warnings,
                             List<ImportedChapter> chapters) {

        static ImportPlan of(ChapterSplitter.Plan plan, List<UUID> ids) {
            List<ImportedChapter> chapters = new ArrayList<>();
            for (int i = 0; i < plan.chapters().size(); i++) {
                ChapterSplitter.Draft draft = plan.chapters().get(i);
                chapters.add(new ImportedChapter(i < ids.size() ? ids.get(i) : null, draft.title(),
                        draft.document().wordCount(), draft.excerpt()));
            }
            return new ImportPlan(plan.rule().name(), plan.documentTitle(), plan.totalWords(), plan.warnings(),
                    chapters);
        }
    }

    public record ImportedChapter(UUID id, String title, int wordCount, String excerpt) {
    }
}
