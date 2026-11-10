package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ficha do lugar (§4.2 do produto), guardada em {@code universe.entities.attributes}. {@code parentId} é o
 * lugar que contém este ("dentro de"); a hierarquia é conferida pelo {@link PlaceService}.
 */
final class PlaceSheet {

    static final Set<String> TYPES = Set.of(
            "city", "town", "neighborhood", "building", "home", "school", "workplace", "shop", "nature",
            "water", "mountain", "realm", "region", "planet", "vehicle", "other");

    /** Campo → tamanho máximo. */
    static final Map<String, Integer> FIELDS = Map.of(
            "type", 20,
            "location", 200,
            "description", 4_000,
            "atmosphere", 4_000,
            "rules", 4_000,
            "history", 4_000,
            "notes", 4_000,
            "parentId", 36);

    private PlaceSheet() {
    }

    static ObjectNode normalize(JsonNode sheet, JsonMapper mapper) {
        ObjectNode result = mapper.createObjectNode();
        SheetText.read(sheet, FIELDS, PlaceSheet::invalid).forEach((key, text) -> {
            if (key.equals("type") && !TYPES.contains(text)) {
                throw invalid("tipo de lugar \"%s\" não existe".formatted(text));
            }
            result.put(key, key.equals("parentId") ? parentId(text) : text);
        });
        return result;
    }

    /** "Dentro de": o id de outro lugar, em forma canônica. */
    private static String parentId(String text) {
        try {
            return UUID.fromString(text).toString();
        } catch (IllegalArgumentException e) {
            throw invalid("\"dentro de\" precisa ser um lugar deste universo").causedBy(e);
        }
    }

    static ApiException invalid(String reason) {
        return ApiException.unprocessable("place_invalid", "Lugar inválido: " + reason + ".");
    }
}
