package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Notas do autor ao lado do texto (§4.1 do produto). Privadas: nunca saem no livro exportado, só no "Baixar tudo".
 * O capítulo precisa ser da pessoa (o {@link ChapterService} confere); nota de outra pessoa responde 404.
 */
@Service
@RequiredArgsConstructor
public class ChapterNoteService {

    static final int MAX_BODY = 5_000;
    static final int MAX_QUOTE = 500;
    static final int MAX_NOTES_PER_CHAPTER = 300;

    private final ChapterNoteRepository repository;
    private final ChapterService chapterService;

    @Transactional(readOnly = true)
    public List<ChapterNote> list(UUID ownerId, UUID chapterId) {
        chapterService.get(ownerId, chapterId);
        return repository.findAllByChapterIdOrderByCreatedAtAsc(chapterId);
    }

    /** {@code id}: o threadId que o editor pôs na marca do trecho. */
    @Transactional
    public ChapterNote create(UUID ownerId, UUID chapterId, UUID id, String body, String quote) {
        chapterService.get(ownerId, chapterId);
        if (id == null) {
            throw invalid("a nota precisa do id do trecho");
        }
        if (repository.existsById(id)) {
            throw ApiException.conflict("note_exists", "Esta nota já existe.", java.util.Map.of());
        }
        if (repository.countByChapterId(chapterId) >= MAX_NOTES_PER_CHAPTER) {
            throw ApiException.unprocessable("too_many_notes",
                    "Este capítulo chegou a %d notas.".formatted(MAX_NOTES_PER_CHAPTER));
        }
        return repository.save(ChapterNote.builder()
                .id(id)
                .chapterId(chapterId)
                .ownerId(ownerId)
                .body(checkBody(body))
                .quote(checkQuote(quote))
                .build());
    }

    @Transactional
    public ChapterNote update(UUID ownerId, UUID noteId, String body, Boolean resolved) {
        ChapterNote note = require(ownerId, noteId);
        if (body != null) {
            note.setBody(checkBody(body));
        }
        if (resolved != null) {
            note.setResolved(resolved);
        }
        return repository.saveAndFlush(note);
    }

    /** Apaga de vez: a nota é do autor, não do livro (o trecho do texto continua igual). */
    @Transactional
    public void delete(UUID ownerId, UUID noteId) {
        repository.delete(require(ownerId, noteId));
    }

    private ChapterNote require(UUID ownerId, UUID noteId) {
        return repository.findById(noteId)
                .filter(n -> n.getOwnerId().equals(ownerId))
                .orElseThrow(() -> ApiException.notFound("note_not_found", "Nota não encontrada."));
    }

    private static String checkBody(String body) {
        String text = body == null ? "" : body.strip();
        if (text.isEmpty()) {
            throw invalid("escreva a nota");
        }
        if (text.length() > MAX_BODY) {
            throw invalid("a nota passou de %d caracteres".formatted(MAX_BODY));
        }
        return text;
    }

    private static String checkQuote(String quote) {
        if (quote == null || quote.isBlank()) {
            return null;
        }
        String text = quote.strip();
        return text.length() <= MAX_QUOTE ? text : text.substring(0, MAX_QUOTE - 1) + "…";
    }

    private static ApiException invalid(String reason) {
        return ApiException.unprocessable("note_invalid", "Nota inválida: " + reason + ".");
    }
}
