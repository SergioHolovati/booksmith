package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Formulário de aparência do personagem (§12.1 da arquitetura, §4.8 do produto). A definição é dado, não
 * código: {@code universe/forms/character-appearance-v2.json}, entregue ao app por
 * {@code GET /api/v1/forms/character-appearance}. Os valores salvos são conferidos contra ela.
 * <p>
 * Tipos de campo: {@code choice} (uma opção), {@code multi} (várias), {@code color} (uma opção com amostra),
 * {@code scale} (inteiro entre {@code min} e {@code max}), {@code text} e {@code longtext}. Com
 * {@code allowCustom}, além das opções vale um texto livre ("Outro").
 */
@Component
public class AppearanceForm {

    public static final int VERSION = 2;
    private static final String RESOURCE = "universe/forms/character-appearance-v2.json";

    private final JsonNode definition;
    private final Map<String, JsonNode> fields = new LinkedHashMap<>();

    public AppearanceForm(JsonMapper mapper) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            this.definition = mapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("Definição do formulário de aparência ausente: " + RESOURCE, e);
        }
        for (JsonNode tab : definition.get("tabs")) {
            for (JsonNode field : tab.get("fields")) {
                if (fields.put(field.get("key").stringValue(), field) != null) {
                    throw new IllegalStateException("Campo repetido no formulário: " + field.get("key"));
                }
            }
        }
    }

    public JsonNode definition() {
        return definition;
    }

    /**
     * Confere os valores contra a definição e devolve a versão limpa: sem campos vazios, textos sem espaços
     * nas pontas e opções repetidas removidas. Campo desconhecido ou valor fora das opções → 422.
     */
    public ObjectNode normalize(JsonNode values, JsonMapper mapper) {
        return FormFields.normalize(fields, values, mapper, AppearanceForm::invalid);
    }

    /**
     * Resumo legível para listas, a partir dos campos marcados com {@code summary} na definição:
     * "Olhos verdes, pele morena, cabelo ruivo, cacheado". Vazio se nada foi preenchido.
     */
    public String summary(JsonNode values) {
        if (values == null || !values.isObject()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode field : fields.values()) {
            JsonNode template = field.get("summary");
            JsonNode value = values.get(field.get("key").stringValue());
            if (template == null || value == null || !value.isString()) {
                continue;
            }
            if (skipped(field, value.stringValue())) {
                continue;
            }
            String label = label(field, value.stringValue());
            parts.add(template.stringValue().replace("{label}", label));
        }
        if (parts.isEmpty()) {
            return "";
        }
        String joined = String.join(", ", parts);
        return joined.substring(0, 1).toUpperCase(Locale.ROOT) + joined.substring(1);
    }

    /** Rótulo da opção como está na definição ("Verdes"), ou o texto livre como o autor escreveu. */
    public String optionLabel(String key, String value) {
        JsonNode field = fields.get(key);
        JsonNode options = field == null ? null : field.get("options");
        if (options != null) {
            for (JsonNode option : options) {
                if (option.get("value").stringValue().equals(value)) {
                    return option.get("label").stringValue();
                }
            }
        }
        return value;
    }

    /** Rótulo da opção em minúsculas, ou o texto livre como o autor escreveu. */
    private static String label(JsonNode field, String value) {
        JsonNode options = field.get("options");
        if (options != null) {
            for (JsonNode option : options) {
                if (option.get("value").stringValue().equals(value)) {
                    return option.get("label").stringValue().toLowerCase(Locale.forLanguageTag("pt-BR"));
                }
            }
        }
        return value;
    }

    private static boolean skipped(JsonNode field, String value) {
        JsonNode skip = field.get("summarySkip");
        if (skip == null) {
            return false;
        }
        for (JsonNode item : skip) {
            if (item.stringValue().equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static ApiException invalid(String key, String reason) {
        String where = key == null ? "" : " (" + key + ")";
        return ApiException.unprocessable("appearance_invalid", "Aparência inválida" + where + ": " + reason + ".");
    }
}
