package com.booksmith.universe.web;

import com.booksmith.identity.api.CurrentUser;
import com.booksmith.universe.internal.CustomFieldService;
import com.booksmith.universe.internal.LoreForm;
import com.booksmith.universe.internal.LoreService;
import com.booksmith.universe.internal.LoreService.LoreData;
import com.booksmith.universe.internal.LoreService.LoreDraft;
import com.booksmith.universe.internal.PlaceService;
import com.booksmith.universe.internal.UniverseEntity;
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
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Contexto do mundo (§4.2 do produto): religiões, sistemas políticos, idiomas e organizações. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
class LoreController {

    private final LoreService loreService;
    private final LoreForm loreForm;
    private final PlaceService placeService;
    private final CurrentUser currentUser;
    private final CustomFieldService customFieldService;

    /** Tipos e campos das fichas. O app monta as telas a partir disto. */
    @GetMapping("/forms/lore")
    JsonNode form() {
        return loreForm.definition();
    }

    @GetMapping("/universes/{universeId}/lore")
    List<LoreSummary> list(@PathVariable UUID universeId, @RequestParam(required = false) String type) {
        Map<UUID, String> places = placeService.names(universeId);
        return loreService.list(currentUser.id(), universeId, type).stream().map(e -> summary(e, places)).toList();
    }

    @PostMapping("/universes/{universeId}/lore")
    @ResponseStatus(HttpStatus.CREATED)
    LoreResponse create(@PathVariable UUID universeId, @RequestBody @Valid CreateLoreRequest request) {
        return response(loreService.create(currentUser.id(), universeId, request.type(), request.name()));
    }

    /** Vários de uma vez, com os lugares pelo nome (o mundo gerado com o mapa). */
    @PostMapping("/universes/{universeId}/lore/batch")
    @ResponseStatus(HttpStatus.CREATED)
    LoreService.LoreImport importBatch(@PathVariable UUID universeId, @RequestBody @Valid BatchRequest request) {
        return loreService.importBatch(currentUser.id(), universeId, request.items());
    }

    @GetMapping("/lore/{loreId}")
    LoreResponse get(@PathVariable UUID loreId) {
        return response(loreService.get(currentUser.id(), loreId));
    }

    @PutMapping("/lore/{loreId}")
    LoreResponse update(@PathVariable UUID loreId, @RequestBody @Valid UpdateLoreRequest request) {
        var data = new LoreData(request.name(), request.aliases(), request.values(), request.placeIds(),
                request.custom());
        return response(loreService.update(currentUser.id(), loreId, request.version(), data));
    }

    @DeleteMapping("/lore/{loreId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID loreId) {
        loreService.delete(currentUser.id(), loreId);
    }

    /** O que existe num lugar: a religião de lá, o governo, os idiomas, as organizações. */
    @GetMapping("/places/{placeId}/lore")
    List<LoreSummary> atPlace(@PathVariable UUID placeId) {
        UniverseEntity place = placeService.get(currentUser.id(), placeId);
        Map<UUID, String> places = placeService.names(place.getUniverseId());
        return loreService.atPlace(currentUser.id(), place.getUniverseId(), placeId).stream()
                .map(e -> summary(e, places)).toList();
    }

    private LoreSummary summary(UniverseEntity item, Map<UUID, String> placeNames) {
        return new LoreSummary(item.getId(), loreService.typeOf(item), item.getName(), Arrays.asList(item.getAliases()),
                loreService.summary(item), places(item, placeNames), item.getUpdatedAt());
    }

    private LoreResponse response(UniverseEntity item) {
        Map<UUID, String> placeNames = placeService.names(item.getUniverseId());
        return new LoreResponse(item.getId(), item.getUniverseId(), loreService.typeOf(item), item.getName(),
                Arrays.asList(item.getAliases()), loreService.values(item), places(item, placeNames),
                customFieldService.values(item, loreService.typeOf(item)), item.getVersion(), item.getUpdatedAt());
    }

    /** Lugares ligados; os que foram excluídos depois somem da lista. */
    private List<PlaceRef> places(UniverseEntity item, Map<UUID, String> placeNames) {
        return loreService.placeIds(item).stream()
                .filter(placeNames::containsKey)
                .map(id -> new PlaceRef(id, placeNames.get(id)))
                .toList();
    }

    record CreateLoreRequest(@NotBlank String type, @NotBlank(message = "Dê um nome.") @Size(max = 120) String name) {
    }

    record UpdateLoreRequest(@NotNull Long version, @NotBlank(message = "Dê um nome.") @Size(max = 120) String name,
                             List<String> aliases, JsonNode values, List<UUID> placeIds, JsonNode custom) {
    }

    record BatchRequest(@NotNull @Size(max = 200) List<LoreDraft> items) {
    }

    record PlaceRef(UUID id, String name) {
    }

    record LoreSummary(UUID id, String type, String name, List<String> aliases, String summary, List<PlaceRef> places,
                       Instant updatedAt) {
    }

    record LoreResponse(UUID id, UUID universeId, String type, String name, List<String> aliases, JsonNode values,
                        List<PlaceRef> places, JsonNode custom, long version, Instant updatedAt) {
    }
}
