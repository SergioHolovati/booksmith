package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Fichas do contexto do mundo (§4.2 do produto): religião, sistema político, idioma e organização. A definição
 * é dado, não código: {@code universe/forms/lore-v1.json}, entregue por {@code GET /api/v1/forms/lore}. Os
 * valores são conferidos pelo mesmo {@link FormFields} dos montadores.
 */
@Component
public class LoreForm {

    private static final String RESOURCE = "universe/forms/lore-v1.json";

    private final JsonNode definition;
    private final Map<String, Map<String, JsonNode>> fieldsByType = new LinkedHashMap<>();

    public LoreForm(JsonMapper mapper) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            this.definition = mapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("Definição das fichas do mundo ausente: " + RESOURCE, e);
        }
        for (var type : definition.get("types").properties()) {
            Map<String, JsonNode> fields = new LinkedHashMap<>();
            type.getValue().get("fields").forEach(f -> fields.put(f.get("key").stringValue(), f));
            fieldsByType.put(type.getKey(), fields);
        }
    }

    public JsonNode definition() {
        return definition;
    }

    public Set<String> types() {
        return fieldsByType.keySet();
    }

    /** Confere o tipo; desconhecido → 422 {@code lore_invalid}. */
    public String requireType(String type) {
        if (type == null || !fieldsByType.containsKey(type)) {
            throw invalid(null, "tipo \"%s\" não existe (use %s)".formatted(type, String.join(", ", types())));
        }
        return type;
    }

    public ObjectNode normalize(String type, JsonNode values, JsonMapper mapper) {
        return FormFields.normalize(fieldsByType.get(requireType(type)), values, mapper, LoreForm::invalid);
    }

    /** Campo que resume o item nas listas (crenças, quem governa, "olá", objetivo). */
    public String summaryField(String type) {
        JsonNode field = definition.get("types").path(type).get("summaryField");
        return field == null ? null : field.stringValue();
    }

    static ApiException invalid(String key, String reason) {
        String where = key == null ? "" : " (" + key + ")";
        return ApiException.unprocessable("lore_invalid", "Ficha inválida" + where + ": " + reason + ".");
    }
}
