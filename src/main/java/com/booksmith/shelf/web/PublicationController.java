package com.booksmith.shelf.web;

import com.booksmith.identity.api.CurrentUser;
import com.booksmith.shelf.internal.ShelfPublishing;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** O autor publica o próprio livro na Estante, atualiza a ficha, republica capítulos e tira do ar. */
@RestController
@RequestMapping("/api/v1/books/{bookId}/publication")
@RequiredArgsConstructor
class PublicationController {

    private final ShelfPublishing publishing;
    private final com.booksmith.shelf.internal.ShelfFeedback feedback;
    private final CurrentUser currentUser;

    /** 204 quando o livro ainda não foi publicado. */
    @GetMapping
    ResponseEntity<ShelfPublishing.Publication> get(@PathVariable UUID bookId) {
        return publishing.publication(currentUser.id(), bookId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Os comentários mais recentes dos leitores na obra inteira. */
    @GetMapping("/comments")
    java.util.List<com.booksmith.shelf.internal.ShelfFeedback.ReaderComment> comments(@PathVariable UUID bookId) {
        return feedback.recent(currentUser.id(), bookId);
    }

    @PutMapping
    ShelfPublishing.Publication publish(@PathVariable UUID bookId, @RequestBody ShelfPublishing.Request request) {
        return publishing.publish(currentUser.id(), bookId, request);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unpublish(@PathVariable UUID bookId) {
        publishing.unpublish(currentUser.id(), bookId);
    }

    @DeleteMapping("/chapters/{chapterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unpublishChapter(@PathVariable UUID bookId, @PathVariable UUID chapterId) {
        publishing.unpublishChapter(currentUser.id(), bookId, chapterId);
    }
}
