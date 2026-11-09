package com.booksmith.manuscript.web;

import com.booksmith.identity.api.CurrentUser;
import com.booksmith.manuscript.internal.TrashService;
import com.booksmith.manuscript.web.ManuscriptDtos.TrashItem;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** Lixeira do usuário: o que foi excluído nos últimos 30 dias e pode voltar. */
@RestController
@RequestMapping("/api/v1/me/trash")
@RequiredArgsConstructor
class TrashController {

    private final TrashService trashService;
    private final CurrentUser currentUser;

    /** Livros e capítulos excluídos, mais recentes primeiro. */
    @GetMapping
    List<TrashItem> list() {
        var trash = trashService.list(currentUser.id());
        return Stream.concat(trash.books().stream().map(TrashItem::from), trash.chapters().stream().map(TrashItem::from))
                .sorted(Comparator.comparing(TrashItem::deletedAt).reversed())
                .toList();
    }

    @PostMapping("/books/{bookId}/restore")
    TrashItem restoreBook(@PathVariable UUID bookId) {
        var book = trashService.restoreBook(currentUser.id(), bookId);
        return new TrashItem(ManuscriptDtos.TrashItemType.BOOK, book.getId(), book.getTitle(), book.getId(),
                book.getTitle(), null, null, null);
    }

    @PostMapping("/chapters/{chapterId}/restore")
    ManuscriptDtos.ChapterSummary restoreChapter(@PathVariable UUID chapterId) {
        return ManuscriptDtos.ChapterSummary.from(trashService.restoreChapter(currentUser.id(), chapterId));
    }
}
