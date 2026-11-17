package com.booksmith.manuscript.web;

import com.booksmith.identity.api.CurrentUser;
import com.booksmith.manuscript.internal.BookService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Exclusão de universo. Fica aqui (manuscritos), e não no módulo do universo, porque precisa saber dos livros
 * vinculados: o universo não conhece os livros (a dependência é dos livros para o universo).
 */
@RestController
@RequestMapping("/api/v1/universes")
@RequiredArgsConstructor
class UniverseDeletionController {

    private final BookService bookService;
    private final CurrentUser currentUser;

    /**
     * Leva o universo para a lixeira. Com livros vinculados: 409 {@code universe_has_books} (com a lista), a não ser
     * com {@code withBooks=true}, que leva os livros juntos para a lixeira.
     */
    @DeleteMapping("/{universeId}")
    DeletedUniverse delete(@PathVariable UUID universeId, @RequestParam(defaultValue = "false") boolean withBooks) {
        return new DeletedUniverse(bookService.deleteUniverse(currentUser.id(), universeId, withBooks));
    }

    /** Os livros que foram junto para a lixeira (vazio se o universo não tinha livros). */
    record DeletedUniverse(List<BookService.LinkedBook> trashedBooks) {
    }
}
