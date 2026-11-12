package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Construtor de universo (§4.2 do produto): passos de um processo criativo (ideia, mundo, povos, mapa, magia,
 * ciência, leis), tudo opcional. A definição é dado, não código: {@code universe/forms/universe-builder-v1.json},
 * entregue ao app por {@code GET /api/v1/forms/universe-builder}.
 * <p>
 * Além dos campos simples, há <b>coleções</b>: listas de itens com os mesmos campos (sistemas de magia, leis do
 * mundo). Cada item tem um {@code id} estável, para o app saber qual está aberto e, depois, para a continuidade
 * citar a lei que o texto quebrou.
 */
@Component
public class UniverseForm {

    public static final int VERSION = 1;
    private static final String RESOURCE = "universe/forms/universe-builder-v1.json";
    private static final Pattern ITEM_ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");

    private final JsonNode definition;
    private final Map<String, JsonNode> fields = new LinkedHashMap<>();
    private final Map<String, Collection> collections = new LinkedHashMap<>();

    private record Collection(String key, int max, Map<String, JsonNode> fields) {
    }

    public UniverseForm(JsonMapper mapper) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            this.definition = mapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("Definição do construtor de universo ausente: " + RESOURCE, e);
        }
        for (JsonNode tab : definition.get("tabs")) {
            for (JsonNode field : tab.get("fields")) {
                add(fields, field);
            }
        }
        for (var entry : definition.get("collections").properties()) {
            Map<String, JsonNode> itemFields = new LinkedHashMap<>();
            entry.getValue().get("fields").forEach(f -> add(itemFields, f));
            collections.put(entry.getKey(), new Collection(entry.getKey(), entry.getValue().get("max").intValue(),
                    itemFields));
            if (fields.containsKey(entry.getKey())) {
                throw new IllegalStateException("Coleção com o mesmo nome de um campo: " + entry.getKey());
            }
        }
    }

    public JsonNode definition() {
        return definition;
    }

    /**
     * Confere o conteúdo do universo e devolve a versão limpa. Itens de coleção sem {@code id} ganham um;
     * campos vazios somem. Campo ou coleção desconhecidos, ou valores fora das opções → 422 {@code universe_invalid}.
     */
    public ObjectNode normalize(JsonNode details, JsonMapper mapper) {
        if (details == null || details.isNull()) {
            return mapper.createObjectNode();
        }
        if (!details.isObject()) {
            throw invalid(null, "envie um objeto com os campos do universo");
        }
        ObjectNode plain = mapper.createObjectNode();
        Map<String, JsonNode> lists = new LinkedHashMap<>();
        for (var entry : details.properties()) {
            if (collections.containsKey(entry.getKey())) {
                lists.put(entry.getKey(), entry.getValue());
            } else {
                plain.set(entry.getKey(), entry.getValue());
            }
        }
        ObjectNode result = FormFields.normalize(fields, plain, mapper, UniverseForm::invalid);
        for (var entry : lists.entrySet()) {
            ArrayNode items = items(collections.get(entry.getKey()), entry.getValue(), mapper);
            if (!items.isEmpty()) {
                result.set(entry.getKey(), items);
            }
        }
        return result;
    }

    /**
     * Junta o conteúdo de dois universos (livro levado para uma série): o que o destino já tem fica; os campos
     * que ele não tem vêm do outro; os itens das coleções (magias, leis) são somados.
     */
    public ObjectNode merge(JsonNode into, JsonNode from, JsonMapper mapper) {
        ObjectNode result = into != null && into.isObject() ? (ObjectNode) into.deepCopy() : mapper.createObjectNode();
        if (from == null || !from.isObject()) {
            return result;
        }
        for (var entry : from.properties()) {
            Collection collection = collections.get(entry.getKey());
            if (collection == null) {
                if (!result.has(entry.getKey())) {
                    result.set(entry.getKey(), entry.getValue().deepCopy());
                }
                continue;
            }
            ArrayNode items = result.has(entry.getKey()) ? (ArrayNode) result.get(entry.getKey()) : result.putArray(entry.getKey());
            Set<String> ids = new HashSet<>();
            items.forEach(i -> ids.add(i.path("id").asString()));
            for (JsonNode item : entry.getValue()) {
                if (items.size() >= collection.max()) {
                    break;
                }
                ObjectNode copy = (ObjectNode) item.deepCopy();
                if (!ids.add(copy.path("id").asString())) {
                    copy.put("id", UUID.randomUUID().toString());
                }
                items.add(copy);
            }
        }
        return result;
    }

    /** Quantos itens a coleção tem (sistemas de magia, leis). */
    public int count(JsonNode details, String collection) {
        JsonNode items = details == null ? null : details.get(collection);
        return items != null && items.isArray() ? items.size() : 0;
    }

    private ArrayNode items(Collection collection, JsonNode value, JsonMapper mapper) {
        ArrayNode result = mapper.createArrayNode();
        if (value == null || value.isNull()) {
            return result;
        }
        if (!value.isArray()) {
            throw invalid(collection.key(), "envie uma lista");
        }
        if (value.size() > collection.max()) {
            throw invalid(collection.key(), "no máximo %d itens".formatted(collection.max()));
        }
        Set<String> ids = new HashSet<>();
        for (JsonNode item : value) {
            if (!item.isObject()) {
                throw invalid(collection.key(), "cada item precisa ser um objeto");
            }
            ObjectNode values = (ObjectNode) item.deepCopy();
            JsonNode idNode = values.remove("id");
            String id = idNode != null && idNode.isString() ? idNode.stringValue() : null;
            if (id == null || !ITEM_ID.matcher(id).matches() || !ids.add(id)) {
                id = UUID.randomUUID().toString();
                ids.add(id);
            }
            ObjectNode normalized = FormFields.normalize(collection.fields(), values, mapper,
                    (key, reason) -> invalid(collection.key() + "." + key, reason));
            ObjectNode withId = mapper.createObjectNode().put("id", id);
            withId.setAll(normalized);
            result.add(withId);
        }
        return result;
    }

    /**
     * O universo em linhas legíveis ("Gêneros: Mistério, Fantasia urbana"), na ordem dos passos, com os rótulos
     * das opções no lugar dos códigos; das coleções, só o título de cada item. Base do contexto das sugestões de
     * IA. Textos longos são cortados em {@code maxText} caracteres.
     */
    public java.util.List<String> describe(JsonNode details, int maxText) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (details == null || !details.isObject()) {
            return lines;
        }
        for (JsonNode tab : definition.get("tabs")) {
            for (JsonNode field : tab.get("fields")) {
                String text = readable(field, details.get(field.get("key").stringValue()), maxText);
                if (!text.isEmpty()) {
                    lines.add(field.get("label").stringValue() + ": " + text);
                }
            }
            JsonNode collectionKey = tab.get("collection");
            if (collectionKey != null && details.get(collectionKey.stringValue()) instanceof ArrayNode items) {
                JsonNode collection = definition.get("collections").get(collectionKey.stringValue());
                String titleField = collection.path("titleField").stringValue("name");
                java.util.List<String> titles = new java.util.ArrayList<>();
                items.forEach(i -> {
                    String title = i.path(titleField).stringValue("").strip();
                    if (!title.isEmpty()) {
                        titles.add(title);
                    }
                });
                if (!titles.isEmpty()) {
                    lines.add(collection.get("label").stringValue() + ": " + String.join(", ", titles));
                }
            }
        }
        return lines;
    }

    private static String readable(JsonNode field, JsonNode value, int maxText) {
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isArray()) {
            java.util.List<String> labels = new java.util.ArrayList<>();
            value.forEach(v -> labels.add(optionLabel(field, v.stringValue(""))));
            return String.join(", ", labels);
        }
        if (value.isNumber()) {
            JsonNode labels = field.get("labels");
            int index = value.intValue() - field.path("min").intValue(1);
            return labels != null && index >= 0 && index < labels.size() ? labels.get(index).stringValue() : "";
        }
        String text = optionLabel(field, value.stringValue("").strip());
        return text.length() > maxText ? text.substring(0, maxText) + "…" : text;
    }

    private static String optionLabel(JsonNode field, String value) {
        for (JsonNode option : field.path("options")) {
            if (value.equals(option.path("value").stringValue(""))) {
                return option.path("label").stringValue(value);
            }
        }
        return value;
    }

    private static void add(Map<String, JsonNode> target, JsonNode field) {
        if (target.put(field.get("key").stringValue(), field) != null) {
            throw new IllegalStateException("Campo repetido no construtor de universo: " + field.get("key"));
        }
    }

    private static ApiException invalid(String key, String reason) {
        String where = key == null ? "" : " (" + key + ")";
        return ApiException.unprocessable("universe_invalid", "Universo inválido" + where + ": " + reason + ".");
    }
}
