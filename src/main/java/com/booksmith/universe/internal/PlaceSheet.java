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
        if (sheet == null || sheet.isNull()) {
            return result;
        }
        if (!sheet.isObject()) {
            throw invalid("envie um objeto com os campos da ficha");
        }
        for (var entry : sheet.properties()) {
            String key = entry.getKey();
            Integer limit = FIELDS.get(key);
            if (limit == null) {
                throw invalid("campo \"%s\" desconhecido".formatted(key));
            }
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                continue;
            }
            if (!value.isString()) {
                throw invalid("\"%s\" precisa ser texto".formatted(key));
            }
            String text = value.stringValue().strip();
            if (text.isEmpty()) {
                continue;
            }
            if (text.length() > limit) {
                throw invalid("\"%s\" passou de %d caracteres".formatted(key, limit));
            }
            if (key.equals("type") && !TYPES.contains(text)) {
                throw invalid("tipo de lugar \"%s\" não existe".formatted(text));
            }
            if (key.equals("parentId")) {
                try {
                    text = UUID.fromString(text).toString();
                } catch (IllegalArgumentException e) {
                    throw invalid("\"dentro de\" precisa ser um lugar deste universo");
                }
            }
            result.put(key, text);
        }
        return result;
    }

    static ApiException invalid(String reason) {
        return ApiException.unprocessable("place_invalid", "Lugar inválido: " + reason + ".");
    }
}
