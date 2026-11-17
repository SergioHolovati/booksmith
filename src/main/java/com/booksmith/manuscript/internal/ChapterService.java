package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.api.PageDesign;
import com.booksmith.manuscript.events.ChapterSaved;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChapterService {

    /** Quantas versões a lista do histórico mostra (as mais recentes). */
    static final int VERSION_LIST_LIMIT = 100;

    private final BookService bookService;
    private final ChapterRepository chapterRepository;
    private final ChapterVersionRepository versionRepository;
    private final ChapterHistory history;
    private final WritingStatsService writingStats;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional
    public ChapterWithBook create(UUID ownerId, UUID bookId, String title) {
        Book book = bookService.require(ownerId, bookId);
        int position = chapterRepository.maxPosition(bookId) + 1;

        Chapter chapter = Chapter.builder()
                .bookId(bookId)
                .position(position)
                // No livro ilustrado, cada capítulo é uma página
                .title(title == null || title.isBlank()
                        ? (book.getGenre() == BookGenre.CHILDREN ? "Página " : "Capítulo ") + position : title.trim())
                .build();
        chapter.applyDocument(ManuscriptDocument.empty(jsonMapper));
        return new ChapterWithBook(chapterRepository.save(chapter), book);
    }

    /** Capítulo junto com o livro: o editor precisa do idioma do livro (corretor ortográfico). */
    @Transactional(readOnly = true)
    public ChapterWithBook get(UUID ownerId, UUID chapterId) {
        Chapter chapter = require(ownerId, chapterId);
        return new ChapterWithBook(chapter, bookService.require(ownerId, chapter.getBookId()));
    }

    /**
     * Salva o conteúdo se {@code expectedVersion} for a versão atual. Se outro dispositivo salvou antes,
     * responde 409 com a versão atual, e o cliente decide como resolver sem perder texto (§9.1).
     * Antes de sobrescrever, o estado anterior pode virar uma versão do histórico (§9.2).
     */
    @Transactional
    public Chapter saveContent(UUID ownerId, UUID chapterId, long expectedVersion, JsonNode content) {
        return saveContent(ownerId, chapterId, expectedVersion, content, WritingStatsService.DEFAULT_ZONE);
    }

    /** Como acima; o saldo de palavras entra na meta do dia, no fuso {@code zone} de quem escreve. */
    @Transactional
    public Chapter saveContent(UUID ownerId, UUID chapterId, long expectedVersion, JsonNode content, ZoneId zone) {
        Chapter chapter = require(ownerId, chapterId);
        requireVersion(chapter, expectedVersion);

        ManuscriptDocument document = ManuscriptDocument.parse(content, jsonMapper);
        if (document.hash().equals(chapter.getContentHash())) {
            return chapter; // nada mudou: não gera versão nova nem evento
        }

        history.snapshotIfDue(chapter, document);
        int before = chapter.getWordCount();
        Chapter saved = applyAndPublish(ownerId, chapter, document);
        writingStats.record(ownerId, saved.getWordCount() - before, zone);
        return saved;
    }

    @Transactional
    public Chapter rename(UUID ownerId, UUID chapterId, String title) {
        Chapter chapter = require(ownerId, chapterId);
        chapter.setTitle(title.trim());
        return chapterRepository.saveAndFlush(chapter);
    }

    static final int MAX_PART_TITLE = 120;

    /** Começa (ou renomeia) uma parte neste capítulo; {@code title} vazio ou null tira a parte. */
    @Transactional
    public Chapter setPart(UUID ownerId, UUID chapterId, String title) {
        Chapter chapter = require(ownerId, chapterId);
        String clean = title == null ? "" : title.strip();
        if (clean.length() > MAX_PART_TITLE) {
            throw ApiException.unprocessable("part_invalid",
                    "O nome da parte passou de %d caracteres.".formatted(MAX_PART_TITLE));
        }
        chapter.setPartTitle(clean.isEmpty() ? null : clean);
        return chapterRepository.saveAndFlush(chapter);
    }

    /**
     * O desenho da página no livro ilustrado (o padrão preenche o que falta). Muda a versão do capítulo, como
     * renomear: o editor manda pela mesma fila do salvamento automático.
     */
    @Transactional
    public Chapter setPage(UUID ownerId, UUID chapterId, PageDesign page) {
        Chapter chapter = require(ownerId, chapterId);
        PageDesign clean = page == null ? PageDesign.DEFAULT : page;
        chapter.setPage(jsonMapper.writeValueAsString(clean));
        return chapterRepository.saveAndFlush(chapter);
    }

    /** Uma página do livro ilustrado para a tela de organizar: o desenho e os parágrafos do texto. */
    public record PagePreview(UUID id, String title, PageDesign page, List<String> paragraphs) {
    }

    static final int PREVIEW_PARAGRAPHS = 12;
    static final int PREVIEW_CHARS = 400;

    /** As páginas na ordem do livro, com o texto para a miniatura (sem buscar capítulo por capítulo). */
    @Transactional(readOnly = true)
    public List<PagePreview> pages(UUID ownerId, UUID bookId) {
        bookService.require(ownerId, bookId);
        return chapterRepository.findAllByBookIdAndDeletedAtIsNullOrderByPositionAsc(bookId).stream()
                .map(c -> new PagePreview(c.getId(), c.getTitle(), pageOf(c), paragraphs(c.getContent())))
                .toList();
    }

    /** O texto de cada parágrafo e título (o resto, como notas de rodapé, fica de fora da miniatura). */
    static List<String> paragraphs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>();
        for (JsonNode block : PAGE_READER.readTree(json).path("content")) {
            String type = block.path("type").asString("");
            if (out.size() < PREVIEW_PARAGRAPHS && ("paragraph".equals(type) || "heading".equals(type))) {
                StringBuilder text = new StringBuilder();
                for (JsonNode inline : block.path("content")) {
                    if ("text".equals(inline.path("type").asString(""))) {
                        text.append(inline.path("text").asString(""));
                    }
                }
                String clean = text.toString().strip();
                if (!clean.isEmpty()) {
                    out.add(clean.length() > PREVIEW_CHARS ? clean.substring(0, PREVIEW_CHARS) + "…" : clean);
                }
            }
        }
        return out;
    }

    /** O desenho guardado, ou o padrão. */
    public static PageDesign pageOf(Chapter chapter) {
        if (chapter.getPage() == null || chapter.getPage().isBlank()) {
            return PageDesign.DEFAULT;
        }
        return PAGE_READER.readValue(chapter.getPage(), PageDesign.class);
    }

    private static final JsonMapper PAGE_READER = JsonMapper.builder().build();

    /**
     * Vai para a lixeira por 30 dias (TrashService). Se o capítulo abria uma parte, ela passa para o capítulo
     * seguinte (a parte não some junto com um capítulo).
     */
    @Transactional
    public void delete(UUID ownerId, UUID chapterId) {
        Chapter chapter = require(ownerId, chapterId);
        if (chapter.getPartTitle() != null) {
            chapterRepository.findAllByBookIdAndDeletedAtIsNullOrderByPositionAsc(chapter.getBookId()).stream()
                    .filter(c -> c.getPosition() > chapter.getPosition())
                    .findFirst()
                    .filter(next -> next.getPartTitle() == null)
                    .ifPresent(next -> next.setPartTitle(chapter.getPartTitle()));
        }
        chapter.setDeletedAt(Instant.now(clock));
    }

    /** {@code orderedIds} precisa conter exatamente os capítulos atuais do livro. */
    @Transactional
    public List<Chapter> reorder(UUID ownerId, UUID bookId, List<UUID> orderedIds) {
        bookService.require(ownerId, bookId);
        List<Chapter> chapters = chapterRepository.findAllByBookIdAndDeletedAtIsNullOrderByPositionAsc(bookId);

        var currentIds = new HashSet<UUID>();
        chapters.forEach(c -> currentIds.add(c.getId()));
        if (orderedIds.size() != chapters.size() || !currentIds.equals(new HashSet<>(orderedIds))) {
            throw ApiException.unprocessable("chapter_order_mismatch",
                    "A nova ordem precisa incluir todos os capítulos do livro, uma vez cada.");
        }

        Map<UUID, Chapter> byId = new java.util.HashMap<>();
        chapters.forEach(c -> byId.put(c.getId(), c));
        for (int i = 0; i < orderedIds.size(); i++) {
            byId.get(orderedIds.get(i)).setPosition(i + 1);
        }
        chapterRepository.flush();
        return orderedIds.stream().map(byId::get).toList();
    }

    // ===== Histórico de versões =====

    @Transactional(readOnly = true)
    public List<ChapterVersion> listVersions(UUID ownerId, UUID chapterId) {
        require(ownerId, chapterId);
        return versionRepository.findAllByChapterIdOrderByCreatedAtDesc(chapterId, PageRequest.of(0, VERSION_LIST_LIMIT));
    }

    @Transactional(readOnly = true)
    public ChapterVersion getVersion(UUID ownerId, UUID chapterId, UUID versionId) {
        require(ownerId, chapterId);
        return requireVersionOf(chapterId, versionId);
    }

    /** "Salvar versão": guarda o estado atual com um nome opcional. */
    @Transactional
    public ChapterVersion saveVersion(UUID ownerId, UUID chapterId, String label) {
        return history.snapshot(require(ownerId, chapterId), ChapterVersion.Reason.MANUAL, label);
    }

    /**
     * Volta o capítulo para uma versão antiga. O estado atual é guardado antes (BEFORE_RESTORE):
     * restaurar nunca apaga texto. Exige a versão atual no If-Match, como o autosave.
     */
    @Transactional
    public ChapterWithBook restoreVersion(UUID ownerId, UUID chapterId, UUID versionId, long expectedVersion) {
        Chapter chapter = require(ownerId, chapterId);
        requireVersion(chapter, expectedVersion);
        ChapterVersion version = requireVersionOf(chapterId, versionId);

        ManuscriptDocument document = ManuscriptDocument.parse(jsonMapper.readTree(version.getContent()), jsonMapper);
        Chapter result = chapter;
        if (!document.hash().equals(chapter.getContentHash())) {
            history.snapshot(chapter, ChapterVersion.Reason.BEFORE_RESTORE, null);
            result = applyAndPublish(ownerId, chapter, document);
        }
        return new ChapterWithBook(result, bookService.require(ownerId, result.getBookId()));
    }

    // ===== Internos =====

    private Chapter applyAndPublish(UUID ownerId, Chapter chapter, ManuscriptDocument document) {
        chapter.applyDocument(document);
        Chapter saved = chapterRepository.saveAndFlush(chapter);
        // A lista de livros é ordenada por atividade recente
        bookService.require(ownerId, saved.getBookId()).setUpdatedAt(Instant.now(clock));
        events.publishEvent(new ChapterSaved(saved.getId(), saved.getBookId(), ownerId,
                saved.getVersion(), saved.getContentHash(), Instant.now(clock)));
        return saved;
    }

    private static void requireVersion(Chapter chapter, long expectedVersion) {
        if (chapter.getVersion() != expectedVersion) {
            throw ApiException.conflict("chapter_version_conflict",
                    "Este capítulo foi alterado em outro dispositivo.",
                    Map.of("currentVersion", chapter.getVersion()));
        }
    }

    private ChapterVersion requireVersionOf(UUID chapterId, UUID versionId) {
        return versionRepository.findByIdAndChapterId(versionId, chapterId)
                .orElseThrow(() -> ApiException.notFound("version_not_found", "Versão não encontrada."));
    }

    private Chapter require(UUID ownerId, UUID chapterId) {
        Chapter chapter = chapterRepository.findByIdAndDeletedAtIsNull(chapterId)
                .orElseThrow(ChapterService::notFound);
        try {
            bookService.require(ownerId, chapter.getBookId());
        } catch (ApiException e) {
            throw notFound().causedBy(e);
        }
        return chapter;
    }

    public record ChapterWithBook(Chapter chapter, Book book) {
    }

    private static ApiException notFound() {
        return ApiException.notFound("chapter_not_found", "Capítulo não encontrado.");
    }
}
