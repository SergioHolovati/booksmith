package com.booksmith.shelf.web;

import com.booksmith.identity.api.AuthenticatedUser;
import com.booksmith.identity.api.CurrentUser;
import com.booksmith.shelf.internal.ShelfReading;
import com.booksmith.shelf.internal.ShelfSocial;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Estante para quem lê. Os GET são públicos (sem conta); com o token, a resposta traz também o que é da pessoa.
 * Seguir, biblioteca, curtir, comentar e denunciar exigem conta.
 */
@RestController
@RequestMapping("/api/v1/shelf")
@RequiredArgsConstructor
class ShelfController {

    private final ShelfReading reading;
    private final com.booksmith.shelf.internal.ShelfShare share;
    private final com.booksmith.shelf.internal.ShelfNotifier notifier;
    private final ShelfSocial social;
    private final CurrentUser currentUser;

    @GetMapping("/works")
    List<ShelfReading.WorkCard> browse(@RequestParam(required = false) String q,
                                       @RequestParam(required = false) String genre,
                                       @RequestParam(required = false) Boolean fanfic,
                                       @RequestParam(required = false) String tag,
                                       @RequestParam(required = false) String maxRating,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(required = false) List<String> excludeTags,
                                       @RequestParam(defaultValue = "recent") String sort) {
        return reading.browse(new ShelfReading.Filter(q, genre, fanfic, tag, maxRating, status, page, excludeTags,
                sort));
    }

    @GetMapping("/works/{slug}")
    ShelfReading.WorkDetail work(@PathVariable String slug) {
        return reading.work(slug, reader());
    }

    @GetMapping("/works/{slug}/chapters/{number}")
    ShelfReading.ChapterView chapter(@PathVariable String slug, @PathVariable int number) {
        return reading.chapter(slug, number, reader());
    }

    @GetMapping("/works/{slug}/chapters/{number}/comments")
    List<ShelfSocial.Comment> comments(@PathVariable String slug, @PathVariable int number) {
        return social.comments(slug, number, reader());
    }

    @GetMapping("/authors/{authorId}")
    ShelfReading.AuthorPage author(@PathVariable UUID authorId) {
        return reading.author(authorId, reader());
    }

    /** Prévia para WhatsApp, redes e buscadores (o Caddy manda só os robôs para cá). */
    @GetMapping(value = "/share", produces = "text/html;charset=UTF-8")
    org.springframework.http.ResponseEntity<String> share(@RequestParam String path) {
        return org.springframework.http.ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePublic())
                .body(share.page(path));
    }

    // ===== Com conta =====

    /** {@code blockId}: o parágrafo comentado (opcional; sem ele, comentário do capítulo). */
    record CommentRequest(@NotBlank @Size(max = 2000) String body, @Size(max = 64) String blockId) {
    }

    @PostMapping("/works/{slug}/chapters/{number}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    ShelfSocial.Comment comment(@PathVariable String slug, @PathVariable int number,
                                @RequestBody @Valid CommentRequest request) {
        return social.comment(currentUser.id(), slug, number, request.body(), request.blockId());
    }

    @DeleteMapping("/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteComment(@PathVariable UUID commentId) {
        social.deleteComment(currentUser.id(), commentId);
    }

    @PutMapping("/works/{slug}/chapters/{number}/like")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void like(@PathVariable String slug, @PathVariable int number) {
        social.like(currentUser.id(), slug, number);
    }

    @DeleteMapping("/works/{slug}/chapters/{number}/like")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unlike(@PathVariable String slug, @PathVariable int number) {
        social.unlike(currentUser.id(), slug, number);
    }

    record ProgressRequest(int chapter, double progress) {
    }

    @PutMapping("/works/{slug}/progress")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void progress(@PathVariable String slug, @RequestBody ProgressRequest request) {
        social.saveProgress(currentUser.id(), slug, request.chapter(), request.progress());
    }

    @PutMapping("/library/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void addToLibrary(@PathVariable String slug) {
        social.addToLibrary(currentUser.id(), slug);
    }

    @DeleteMapping("/library/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeFromLibrary(@PathVariable String slug) {
        social.removeFromLibrary(currentUser.id(), slug);
    }

    @GetMapping("/me/library")
    List<ShelfReading.WorkCard> library() {
        return reading.library(currentUser.id());
    }

    @GetMapping("/me/following")
    List<ShelfReading.WorkCard> following() {
        return reading.following(currentUser.id());
    }

    /** {@code chapterEmails}: e-mail de capítulo novo de quem a pessoa segue (ligado por padrão). */
    record Preferences(boolean chapterEmails) {
    }

    @GetMapping("/me/preferences")
    Preferences preferences() {
        return new Preferences(notifier.chapterEmails(currentUser.id()));
    }

    @PutMapping("/me/preferences")
    Preferences savePreferences(@RequestBody Preferences request) {
        notifier.setChapterEmails(currentUser.id(), request.chapterEmails());
        return request;
    }

    @PutMapping("/authors/{authorId}/follow")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void follow(@PathVariable UUID authorId) {
        social.follow(currentUser.id(), authorId);
    }

    @DeleteMapping("/authors/{authorId}/follow")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unfollow(@PathVariable UUID authorId) {
        social.unfollow(currentUser.id(), authorId);
    }

    record ReportRequest(@NotBlank String targetType, @NotNull UUID targetId, @NotBlank String reason,
                         @Size(max = 1000) String details) {
    }

    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void report(@RequestBody @Valid ReportRequest request) {
        social.report(currentUser.id(), request.targetType(), request.targetId(), request.reason(), request.details());
    }

    private UUID reader() {
        return currentUser.find().map(AuthenticatedUser::id).orElse(null);
    }
}
