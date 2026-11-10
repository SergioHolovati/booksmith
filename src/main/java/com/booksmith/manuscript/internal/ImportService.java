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
 * Importação de arquivos (.docx, .odt, .md, .txt) para um livro: primeiro a prévia (como o arquivo seria dividido),
 * depois a importação, que acrescenta os capítulos no fim do livro. Nada do que já existe é alterado.
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

        int position = chapterRepository.maxPosition(bookId);
        List<Chapter> created = new ArrayList<>();
        for (ChapterSplitter.Draft draft : plan.chapters()) {
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
        log.info("Importação de arquivo: {} capítulos, {} palavras no livro {}", created.size(), plan.totalWords(), bookId);
        return ImportPlan.of(plan, created.stream().map(Chapter::getId).toList());
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
