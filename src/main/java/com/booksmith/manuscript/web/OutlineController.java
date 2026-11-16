package com.booksmith.manuscript.web;

import com.booksmith.identity.api.CurrentUser;
import com.booksmith.manuscript.internal.ImportService;
import com.booksmith.manuscript.internal.ImportService.ImportPlan;
import com.booksmith.manuscript.internal.ImportService.OutlineItemView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * "Criar pelo sumário": o escritor cola o sumário (uma linha por capítulo; "Parte 1", "2.1 - …" e linhas recuadas
 * viram parte e subcapítulo), revisa a estrutura e o Booksmith cria os capítulos vazios no fim do livro.
 */
@RestController
@RequestMapping("/api/v1/books/{bookId}/outline")
@RequiredArgsConstructor
class OutlineController {

    private final ImportService importService;
    private final CurrentUser currentUser;

    /** A estrutura que o sumário colado descreve. Não grava nada. */
    @PostMapping("/preview")
    List<OutlineItemView> preview(@PathVariable UUID bookId, @RequestBody @Valid PreviewRequest request) {
        return importService.previewOutline(currentUser.id(), bookId, request.text());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ImportPlan create(@PathVariable UUID bookId, @RequestBody @Valid CreateRequest request) {
        return importService.createFromOutline(currentUser.id(), bookId, request.items().stream()
                .map(i -> new com.booksmith.manuscript.internal.StructureBuilder.OutlineEntry(i.kind(), i.title(), -1))
                .toList());
    }

    record PreviewRequest(@NotNull @Size(max = 40_000) String text) {
    }

    record Item(@NotNull String kind, @NotNull @Size(max = 300) String title) {
    }

    record CreateRequest(@NotNull @Size(min = 1, max = 400) List<@Valid Item> items) {
    }
}
