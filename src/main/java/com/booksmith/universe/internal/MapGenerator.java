package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Set;

/**
 * Ajustes do gerador de mapa (§12.3). O mapa é gerado no app, sem IA, a partir de uma semente: o servidor só
 * guarda a semente e os ajustes, para refazer o mesmo mapa e, depois, transformar o que ele tem (países,
 * cidades, florestas) em lugares do universo. {@code version} é a do algoritmo: outra versão gera outro mapa
 * com a mesma semente.
 */
final class MapGenerator {

    private static final int MAX_SEED = 40;
    private static final Map<String, Set<String>> CHOICES = Map.of(
            "size", Set.of("small", "medium", "large"),
            "land", Set.of("little", "medium", "much"),
            "climate", Set.of("cold", "temperate", "hot", "varied"),
            "style", Set.of("atlas", "parchment"));
    private static final int MAX_COUNTRIES = 12;

    private MapGenerator() {
    }

    static ObjectNode normalize(JsonNode generator, JsonMapper mapper) {
        if (!generator.isObject()) {
            throw invalid("envie um objeto com a semente e os ajustes");
        }
        ObjectNode result = mapper.createObjectNode();
        for (var entry : generator.properties()) {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            switch (key) {
                case "version" -> result.put(key, integer(value, key, 1, 99));
                case "countries" -> result.put(key, integer(value, key, 1, MAX_COUNTRIES));
                case "seed" -> {
                    if (!value.isString() || value.stringValue().isBlank() || value.stringValue().length() > MAX_SEED) {
                        throw invalid("a semente é um texto de até %d caracteres".formatted(MAX_SEED));
                    }
                    result.put(key, value.stringValue().strip());
                }
                default -> {
                    Set<String> options = CHOICES.get(key);
                    if (options == null) {
                        throw invalid("ajuste \"%s\" desconhecido".formatted(key));
                    }
                    if (!value.isString() || !options.contains(value.stringValue())) {
                        throw invalid("\"%s\" aceita %s".formatted(key, String.join(", ", options)));
                    }
                    result.put(key, value.stringValue());
                }
            }
        }
        if (!result.has("seed") || !result.has("version")) {
            throw invalid("faltam a semente e a versão do gerador");
        }
        return result;
    }

    private static int integer(JsonNode value, String key, int min, int max) {
        if (!value.isIntegralNumber() || value.intValue() < min || value.intValue() > max) {
            throw invalid("\"%s\" vai de %d a %d".formatted(key, min, max));
        }
        return value.intValue();
    }

    private static ApiException invalid(String reason) {
        return ApiException.unprocessable("map_generator_invalid", "Ajustes do mapa inválidos: " + reason + ".");
    }
}
