package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Campos de texto de uma ficha (personagem, lugar): o que é comum a todas. Recusa campo desconhecido, valor que não
 * é texto e texto acima do limite do campo; devolve os preenchidos, sem espaços nas pontas, na ordem enviada. As
 * regras próprias de cada ficha (papel do personagem, tipo do lugar) ficam nela.
 */
final class SheetText {

    private SheetText() {
    }

    /**
     * @param fields  campo → limite de caracteres
     * @param invalid monta o erro da ficha a partir do motivo ("campo \"x\" desconhecido")
     */
    static Map<String, String> read(JsonNode sheet, Map<String, Integer> fields, Function<String, ApiException> invalid) {
        Map<String, String> values = new LinkedHashMap<>();
        if (sheet == null || sheet.isNull()) {
            return values;
        }
        if (!sheet.isObject()) {
            throw invalid.apply("envie um objeto com os campos da ficha");
        }
        for (var entry : sheet.properties()) {
            String key = entry.getKey();
            Integer limit = fields.get(key);
            if (limit == null) {
                throw invalid.apply("campo \"%s\" desconhecido".formatted(key));
            }
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                continue;
            }
            if (!value.isString()) {
                throw invalid.apply("\"%s\" precisa ser texto".formatted(key));
            }
            String text = value.stringValue().strip();
            if (text.length() > limit) {
                throw invalid.apply("\"%s\" passou de %d caracteres".formatted(key, limit));
            }
            if (!text.isEmpty()) {
                values.put(key, text);
            }
        }
        return values;
    }
}
