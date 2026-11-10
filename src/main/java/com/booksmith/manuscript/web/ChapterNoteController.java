package com.booksmith.manuscript.web;

import com.booksmith.identity.api.CurrentUser;
import com.booksmith.manuscript.internal.ChapterNote;
import com.booksmith.manuscript.internal.ChapterNoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Notas do autor ao lado do texto (§4.1 do produto). */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
class ChapterNoteController {

    private final ChapterNoteService noteService;
    private final CurrentUser currentUser;

    @GetMapping("/chapters/{chapterId}/notes")
    List<NoteResponse> list(@PathVariable UUID chapterId) {
        return noteService.list(currentUser.id(), chapterId).stream().map(ChapterNoteController::response).toList();
    }

    /** {@code id}: o threadId da marca que o editor pôs no trecho. */
    @PostMapping("/chapters/{chapterId}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    NoteResponse create(@PathVariable UUID chapterId, @RequestBody CreateNote request) {
        return response(noteService.create(currentUser.id(), chapterId, request.id(), request.body(), request.quote()));
    }

    /** Campos ausentes ficam como estão. */
    @PatchMapping("/notes/{noteId}")
    NoteResponse update(@PathVariable UUID noteId, @RequestBody UpdateNote request) {
        return response(noteService.update(currentUser.id(), noteId, request.body(), request.resolved()));
    }

    @DeleteMapping("/notes/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID noteId) {
        noteService.delete(currentUser.id(), noteId);
    }

    private static NoteResponse response(ChapterNote n) {
        return new NoteResponse(n.getId(), n.getChapterId(), n.getBody(), n.getQuote(), n.isResolved(), n.getCreatedAt(),
                n.getUpdatedAt());
    }

    record CreateNote(UUID id, String body, String quote) {
    }

    record UpdateNote(String body, Boolean resolved) {
    }

    record NoteResponse(UUID id, UUID chapterId, String body, String quote, boolean resolved, Instant createdAt,
                        Instant updatedAt) {
    }
}
