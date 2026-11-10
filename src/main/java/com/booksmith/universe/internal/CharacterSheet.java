package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Set;

/**
 * Ficha do personagem (§4.3 do produto): os campos de texto que não são aparência. Fica em
 * {@code universe.entities.attributes}. Campos fixos por enquanto; os personalizáveis por tipo de entidade
 * (§7.4, {@code entity_types.field_defs}) entram junto com lugares e facções.
 */
final class CharacterSheet {

    static final Set<String> ROLES = Set.of("protagonist", "antagonist", "supporting", "minor");

    /** Campo → tamanho máximo. */
    static final Map<String, Integer> FIELDS = Map.ofEntries(
            Map.entry("role", 20),
            Map.entry("age", 60),
            Map.entry("birthday", 60),
            Map.entry("occupation", 120),
            Map.entry("personality", 4_000),
            Map.entry("voice", 4_000),
            Map.entry("fears", 4_000),
            Map.entry("desires", 4_000),
            Map.entry("secrets", 4_000),
            Map.entry("backstory", 4_000),
            Map.entry("notes", 4_000));

    static final int MAX_ALIASES = 20;
    static final int MAX_ALIAS = 80;

    private CharacterSheet() {
    }

    static ObjectNode normalize(JsonNode sheet, JsonMapper mapper) {
        ObjectNode result = mapper.createObjectNode();
        SheetText.read(sheet, FIELDS, CharacterSheet::invalid).forEach((key, text) -> {
            if (key.equals("role") && !ROLES.contains(text)) {
                throw invalid("papel \"%s\" não existe".formatted(text));
            }
            result.put(key, text);
        });
        return result;
    }

    static String[] aliases(java.util.List<String> aliases) {
        if (aliases == null) {
            return new String[0];
        }
        var cleaned = aliases.stream()
                .filter(a -> a != null && !a.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
        if (cleaned.size() > MAX_ALIASES || cleaned.stream().anyMatch(a -> a.length() > MAX_ALIAS)) {
            throw invalid("até %d apelidos, com até %d caracteres cada".formatted(MAX_ALIASES, MAX_ALIAS));
        }
        return cleaned.toArray(String[]::new);
    }

    private static ApiException invalid(String reason) {
        return ApiException.unprocessable("character_invalid", "Ficha inválida: " + reason + ".");
    }
}
