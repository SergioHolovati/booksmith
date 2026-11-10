package com.booksmith.universe.web;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.api.CurrentUser;
import com.booksmith.universe.internal.UniverseTrashService;
import com.booksmith.universe.internal.UniverseTrashService.ItemType;
import com.booksmith.universe.internal.UniverseTrashService.TrashedItem;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Lixeira do universo: personagens, lugares, fichas do mundo, eventos e relações dos últimos 30 dias. */
@RestController
@RequestMapping("/api/v1/me/trash/universe")
@RequiredArgsConstructor
class UniverseTrashController {

    private final UniverseTrashService trashService;
    private final CurrentUser currentUser;

    @GetMapping
    List<TrashedItem> list() {
        return trashService.list(currentUser.id());
    }

    /** {@code type}: character, place, lore, event ou relation. */
    @PostMapping("/{type}/{id}/restore")
    TrashedItem restore(@PathVariable String type, @PathVariable UUID id) {
        ItemType itemType;
        try {
            itemType = ItemType.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("trash_item_not_found", "Este item não está mais na lixeira.").causedBy(e);
        }
        return trashService.restore(currentUser.id(), itemType, id);
    }
}
