package com.booksmith.universe.web;

import com.booksmith.identity.api.CurrentUser;
import com.booksmith.universe.internal.CustomFieldService;
import com.booksmith.universe.internal.GalleryService;
import com.booksmith.universe.internal.PlaceService;
import com.booksmith.universe.internal.PlaceService.PlaceData;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Lugares da Bíblia do Universo (§4.2 do produto). */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
class PlaceController {

    private final PlaceService placeService;
    private final GalleryService galleryService;
    private final CurrentUser currentUser;
    private final JsonMapper jsonMapper;
    private final CustomFieldService customFieldService;

    @GetMapping("/universes/{universeId}/places")
    List<PlaceSummary> list(@PathVariable UUID universeId) {
        List<UniverseEntity> places = placeService.list(currentUser.id(), universeId);
        Map<UUID, String> names = placeService.names(universeId);
        Map<UUID, String> portraits = galleryService.portraits(universeId);
        return places.stream().map(place -> summary(place, names, portraits.get(place.getId()))).toList();
    }

    @PostMapping("/universes/{universeId}/places")
    @ResponseStatus(HttpStatus.CREATED)
    PlaceResponse create(@PathVariable UUID universeId, @RequestBody @Valid CreatePlaceRequest request) {
        return response(placeService.create(currentUser.id(), universeId, request.name()));
    }

    /** Lugares do mapa gerado (países, cidades, florestas…), criados de uma vez com posição no mapa. */
    @PostMapping("/universes/{universeId}/places/from-map")
    @ResponseStatus(HttpStatus.CREATED)
    PlaceService.MapImport importFromMap(@PathVariable UUID universeId, @RequestBody @Valid MapImportRequest request) {
        return placeService.importFromMap(currentUser.id(), universeId, request.places());
    }

    @GetMapping("/places/{placeId}")
    PlaceResponse get(@PathVariable UUID placeId) {
        return response(placeService.get(currentUser.id(), placeId));
    }

    /** Salva a ficha inteira; {@code version} é a versão aberta no app (409 se outro aparelho salvou antes). */
    @PutMapping("/places/{placeId}")
    PlaceResponse update(@PathVariable UUID placeId, @RequestBody @Valid UpdatePlaceRequest request) {
        var data = new PlaceData(request.name(), request.aliases(), request.sheet(), request.custom());
        return response(placeService.update(currentUser.id(), placeId, request.version(), data));
    }

    @DeleteMapping("/places/{placeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID placeId) {
        placeService.delete(currentUser.id(), placeId);
    }

    private PlaceSummary summary(UniverseEntity place, Map<UUID, String> names, String portraitUrl) {
        JsonNode sheet = jsonMapper.readTree(place.getAttributes());
        UUID parentId = placeService.parentOf(place).orElse(null);
        return new PlaceSummary(place.getId(), place.getName(), Arrays.asList(place.getAliases()),
                text(sheet, "type"), parentId, parentId == null ? null : names.get(parentId),
                excerpt(text(sheet, "description")), place.getMapX(), place.getMapY(), portraitUrl,
                place.getUpdatedAt());
    }

    private PlaceResponse response(UniverseEntity place) {
        return new PlaceResponse(place.getId(), place.getUniverseId(), place.getName(),
                Arrays.asList(place.getAliases()), jsonMapper.readTree(place.getAttributes()), place.getMapX(),
                place.getMapY(), place.getPortraitId(), galleryService.portrait(place),
                customFieldService.values(place, UniverseEntity.PLACE), place.getVersion(), place.getUpdatedAt());
    }

    private static String text(JsonNode sheet, String key) {
        JsonNode value = sheet.get(key);
        return value == null ? null : value.stringValue();
    }

    private static String excerpt(String text) {
        if (text == null || text.length() <= 140) {
            return text;
        }
        int cut = text.lastIndexOf(' ', 140);
        return text.substring(0, cut > 40 ? cut : 140).strip() + "…";
    }

    record CreatePlaceRequest(@NotBlank(message = "Dê um nome ao lugar.") @Size(max = 120) String name) {
    }

    record UpdatePlaceRequest(
            @NotNull Long version,
            @NotBlank(message = "Dê um nome ao lugar.") @Size(max = 120) String name,
            List<String> aliases,
            JsonNode sheet,
            JsonNode custom) {
    }

    record MapImportRequest(@NotNull @Size(max = 300) List<PlaceService.MapPlace> places) {
    }

    record PlaceSummary(UUID id, String name, List<String> aliases, String type, UUID parentId, String parentName,
                        String excerpt, Float mapX, Float mapY, String portraitUrl, Instant updatedAt) {
    }

    record PlaceResponse(UUID id, UUID universeId, String name, List<String> aliases, JsonNode sheet, Float mapX,
                         Float mapY, UUID portraitId, String portraitUrl, JsonNode custom, long version,
                         Instant updatedAt) {
    }
}
