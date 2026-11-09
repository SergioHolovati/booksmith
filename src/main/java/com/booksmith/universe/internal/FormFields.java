package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Validação dos formulários definidos por dados (montador de personagem, construtor de universo).
 * <p>
 * Tipos de campo: {@code choice} (uma opção), {@code multi} (várias), {@code color} (uma opção com amostra),
 * {@code scale} (inteiro entre {@code min} e {@code max}), {@code text} e {@code longtext}. Com
 * {@code allowCustom}, além das opções vale um texto livre ("Outro").
 */
final class FormFields {

    static final int MAX_CUSTOM = 80;
    static final int MAX_TEXT = 200;
    static final int MAX_LONGTEXT = 2_000;
    static final int MAX_MULTI = 20;

    /** Erro de validação de um campo ({@code key} nulo: o objeto inteiro). */
    @FunctionalInterface
    interface Invalid {
        ApiException of(String key, String reason);
    }

    private FormFields() {
    }

    /**
     * Confere os valores contra os campos e devolve a versão limpa: sem campos vazios, textos sem espaços nas
     * pontas e opções repetidas removidas. Campo desconhecido ou valor fora das opções → erro de {@code invalid}.
     */
    static ObjectNode normalize(Map<String, JsonNode> fields, JsonNode values, JsonMapper mapper, Invalid invalid) {
        ObjectNode result = mapper.createObjectNode();
        if (values == null || values.isNull()) {
            return result;
        }
        if (!values.isObject()) {
            throw invalid.of(null, "envie um objeto com os campos do formulário");
        }
        for (var entry : values.properties()) {
            String key = entry.getKey();
            JsonNode field = fields.get(key);
            if (field == null) {
                throw invalid.of(key, "campo desconhecido");
            }
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                continue;
            }
            put(result, key, field, value, invalid);
        }
        return result;
    }

    private static void put(ObjectNode result, String key, JsonNode field, JsonNode value, Invalid invalid) {
        switch (field.get("type").stringValue()) {
            case "choice", "color" -> {
                String choice = option(field, key, value, invalid);
                if (choice != null) {
                    result.put(key, choice);
                }
            }
            case "multi" -> {
                if (!value.isArray()) {
                    throw invalid.of(key, "envie uma lista");
                }
                if (value.size() > MAX_MULTI) {
                    throw invalid.of(key, "no máximo %d itens".formatted(MAX_MULTI));
                }
                Set<String> chosen = new LinkedHashSet<>();
                for (JsonNode item : value) {
                    String choice = option(field, key, item, invalid);
                    if (choice != null) {
                        chosen.add(choice);
                    }
                }
                if (!chosen.isEmpty()) {
                    ArrayNode array = result.putArray(key);
                    chosen.forEach(array::add);
                }
            }
            case "scale" -> {
                int min = field.get("min").intValue();
                int max = field.get("max").intValue();
                if (!value.isIntegralNumber() || value.intValue() < min || value.intValue() > max) {
                    throw invalid.of(key, "use um número de %d a %d".formatted(min, max));
                }
                result.put(key, value.intValue());
            }
            case "text", "longtext" -> {
                if (!value.isString()) {
                    throw invalid.of(key, "envie um texto");
                }
                String text = value.stringValue().strip();
                int limit = "text".equals(field.get("type").stringValue()) ? MAX_TEXT : MAX_LONGTEXT;
                if (text.length() > limit) {
                    throw invalid.of(key, "no máximo %d caracteres".formatted(limit));
                }
                if (!text.isEmpty()) {
                    result.put(key, text);
                }
            }
            default -> throw new IllegalStateException("Tipo de campo desconhecido em " + key);
        }
    }

    /** Valor de uma opção: um dos {@code value} da definição ou, com {@code allowCustom}, um texto curto. */
    private static String option(JsonNode field, String key, JsonNode value, Invalid invalid) {
        if (!value.isString()) {
            throw invalid.of(key, "envie o valor da opção como texto");
        }
        String text = value.stringValue().strip();
        if (text.isEmpty()) {
            return null;
        }
        for (JsonNode option : field.get("options")) {
            if (option.get("value").stringValue().equals(text)) {
                return text;
            }
        }
        JsonNode custom = field.get("allowCustom");
        if (custom != null && custom.booleanValue()) {
            if (text.length() > MAX_CUSTOM) {
                throw invalid.of(key, "texto livre com no máximo %d caracteres".formatted(MAX_CUSTOM));
            }
            return text;
        }
        throw invalid.of(key, "opção \"%s\" não existe".formatted(text));
    }
}
