package com.booksmith.manuscript.web;

import com.booksmith.common.web.PageResponse;
import com.booksmith.identity.api.CurrentUser;
import com.booksmith.manuscript.api.BookLayout;
import com.booksmith.manuscript.api.Manuscripts;
import com.booksmith.manuscript.internal.BookService;
import com.booksmith.manuscript.web.ManuscriptDtos.BookDetailResponse;
import com.booksmith.manuscript.web.ManuscriptDtos.BookPagesRequest;
import com.booksmith.manuscript.web.ManuscriptDtos.BookRequest;
import com.booksmith.manuscript.web.ManuscriptDtos.BookSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/books")
@RequiredArgsConstructor
class BookController {

    private static final int MAX_PAGE_SIZE = 50;

    private final BookService bookService;
    private final CurrentUser currentUser;

    @GetMapping
    PageResponse<BookSummary> list(@RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "12") int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "updatedAt"));
        return PageResponse.from(bookService.list(currentUser.id(), pageable)
                .map(b -> BookSummary.from(b.book(), b.stats())));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    BookDetailResponse create(@RequestBody @Valid BookRequest request) {
        var book = bookService.create(currentUser.id(), request.toData());
        return BookDetailResponse.from(new BookService.BookDetail(book, java.util.List.of()));
    }

    @GetMapping("/{bookId}")
    BookDetailResponse get(@PathVariable UUID bookId) {
        return BookDetailResponse.from(bookService.get(currentUser.id(), bookId));
    }

    @PutMapping("/{bookId}")
    BookDetailResponse update(@PathVariable UUID bookId, @RequestBody @Valid BookRequest request) {
        bookService.update(currentUser.id(), bookId, request.toData());
        return BookDetailResponse.from(bookService.get(currentUser.id(), bookId));
    }

    /** Créditos, dedicatória, epígrafe, agradecimentos e "sobre o autor" (saem no livro baixado). */
    @PutMapping("/{bookId}/pages")
    Manuscripts.BookPages updatePages(@PathVariable UUID bookId, @RequestBody @Valid BookPagesRequest request) {
        return bookService.updatePages(currentUser.id(), bookId, request.toPages());
    }

    /** Diagramação do PDF: tamanho, letra, entrelinha, margens, abertura de capítulo e quebra de cena. */
    @PutMapping("/{bookId}/layout")
    BookLayout updateLayout(@PathVariable UUID bookId, @RequestBody LayoutRequest request) {
        return bookService.updateLayout(currentUser.id(), bookId, request.toLayout());
    }

    /** Recebido solto e conferido no {@link BookLayout} (422 {@code layout_invalid}), fora do Jackson. */
    record LayoutRequest(String trim, Double fontSize, String leading, String margins, String chapterStyle,
                         String sceneBreak) {

        BookLayout toLayout() {
            return new BookLayout(trim, fontSize, leading, margins, chapterStyle, sceneBreak);
        }
    }

    /** Entrar no universo de outro livro (série): ver {@link BookService#moveToUniverse}. */
    @PutMapping("/{bookId}/universe")
    BookService.UniverseMove moveToUniverse(@PathVariable UUID bookId,
                                            @RequestBody @Valid MoveToUniverseRequest request) {
        return bookService.moveToUniverse(currentUser.id(), bookId, request.universeId());
    }

    record MoveToUniverseRequest(
            @NotNull(message = "Escolha o universo.") UUID universeId) {
    }

    @DeleteMapping("/{bookId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID bookId) {
        bookService.delete(currentUser.id(), bookId);
    }
}
