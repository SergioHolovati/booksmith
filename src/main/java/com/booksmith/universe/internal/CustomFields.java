package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Campos próprios do autor (§4.2 do produto): cada universo acrescenta campos às fichas de personagem, de lugar
 * e de cada tipo de ficha do mundo ("Signo" no personagem, "Moeda" no país). A definição usa o mesmo formato
 * dos formulários definidos por dados, e os valores são conferidos pelo mesmo {@link FormFields}.
 * <p>
 * O autor escreve só rótulos; a chave do campo e o valor de cada opção são gerados aqui e não mudam mais, então
 * renomear um campo ou uma opção não perde o que já foi preenchido. Campo apagado: os valores ficam guardados,
 * mas deixam de aparecer e saem na próxima vez que a ficha for salva.
 */
@Slf4j
@Component
public class CustomFields {

    public static final int MAX_FIELDS = 30;
    public static final int MAX_LABEL = 60;
    public static final int MAX_OPTIONS = 30;
    static final Set<String> TYPES = Set.of("text", "longtext", "choice", "multi", "scale");
    private static final Pattern KEY = Pattern.compile("[a-z0-9_]{1,40}");
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";

    private final LoreForm loreForm;
    private final SecureRandom random = new SecureRandom();

    public CustomFields(LoreForm loreForm) {
        this.loreForm = loreForm;
    }

    /** Onde dá para criar campos: personagem, lugar e cada tipo de ficha do mundo. */
    public Set<String> scopes() {
        Set<String> scopes = new LinkedHashSet<>();
        scopes.add(UniverseEntity.CHARACTER);
        scopes.add(UniverseEntity.PLACE);
        scopes.addAll(loreForm.types());
        return scopes;
    }

    public String requireScope(String scope) {
        if (scope == null || !scopes().contains(scope)) {
            throw invalid("tipo de ficha \"%s\" não existe".formatted(scope));
        }
        return scope;
    }

    /**
     * Confere e completa a lista de campos de um tipo de ficha: rótulos obrigatórios, tipos conhecidos, chaves e
     * valores de opção gerados quando faltam (os que vieram são mantidos, para não perder o preenchido).
     */
    public ArrayNode define(JsonNode fields, JsonMapper mapper) {
        if (fields == null || !fields.isArray()) {
            throw invalid("envie a lista de campos");
        }
        if (fields.size() > MAX_FIELDS) {
            throw invalid("no máximo %d campos por tipo de ficha".formatted(MAX_FIELDS));
        }
        ArrayNode result = mapper.createArrayNode();
        Set<String> keys = new HashSet<>();
        for (JsonNode field : fields) {
            ObjectNode clean = result.addObject();
            String label = label(field.path("label"), "Dê um nome a cada campo.");
            String type = field.path("type").asString("");
            if (!TYPES.contains(type)) {
                throw invalid("o campo \"%s\" tem um tipo desconhecido".formatted(label));
            }
            String key = keyOf(field.path("key"), keys, "f_");
            keys.add(key);
            clean.put("key", key).put("label", label).put("type", type);
            switch (type) {
                case "choice", "multi" -> options(field, label, clean);
                case "scale" -> scale(field, clean);
                default -> {
                    // texto curto ou longo: nada além do rótulo
                }
            }
        }
        return result;
    }

    /** Os campos de um tipo de ficha, pela chave, a partir da definição guardada no universo. */
    public Map<String, JsonNode> fieldsOf(JsonNode definition, String scope) {
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        definition.path(scope).forEach(f -> fields.put(f.get("key").stringValue(), f));
        return fields;
    }

    /**
     * Confere os valores de uma ficha. Chaves de campos que não existem mais são descartadas em silêncio (o
     * campo foi apagado, talvez em outro aparelho); valor de tipo errado é erro.
     */
    public ObjectNode values(JsonNode definition, String scope, JsonNode values, JsonMapper mapper) {
        Map<String, JsonNode> fields = fieldsOf(definition, scope);
        ObjectNode known = mapper.createObjectNode();
        if (values != null && values.isObject()) {
            values.properties().forEach(e -> {
                if (fields.containsKey(e.getKey())) {
                    known.set(e.getKey(), e.getValue());
                }
            });
        } else if (values != null && !values.isNull()) {
            throw invalid("envie os campos próprios como um objeto");
        }
        return FormFields.normalize(fields, known, mapper, CustomFields::invalidValue);
    }

    /**
     * O que mostrar de uma ficha: só os valores que ainda cabem na definição. O autor pode ter apagado o campo,
     * trocado o tipo ("texto" virou "escala") ou tirado uma opção; o valor antigo fica guardado, mas não volta para
     * o app, que assim nunca o reenvia (e a ficha continua salvando).
     */
    public ObjectNode current(JsonNode definition, String scope, JsonNode stored, JsonMapper mapper) {
        Map<String, JsonNode> fields = fieldsOf(definition, scope);
        ObjectNode result = mapper.createObjectNode();
        stored.properties().forEach(e -> {
            JsonNode field = fields.get(e.getKey());
            if (field == null) {
                return;
            }
            ObjectNode single = mapper.createObjectNode();
            single.set(e.getKey(), e.getValue());
            try {
                result.setAll(FormFields.normalize(Map.of(e.getKey(), field), single, mapper, CustomFields::invalidValue));
            } catch (ApiException stale) {
                // valor de antes da mudança no campo: fica guardado, mas não aparece
                log.debug("Campo próprio {} com valor antigo: {}", e.getKey(), stale.getMessage());
            }
        });
        return result;
    }

    /** Juntar universos: os campos que o destino não tem vêm junto, para os valores continuarem aparecendo. */
    public ObjectNode merge(JsonNode target, JsonNode source, JsonMapper mapper) {
        ObjectNode result = target.isObject() ? ((ObjectNode) target).deepCopy() : mapper.createObjectNode();
        for (var scope : source.properties()) {
            ArrayNode fields = result.has(scope.getKey()) ? (ArrayNode) result.get(scope.getKey())
                    : result.putArray(scope.getKey());
            Set<String> keys = new HashSet<>();
            fields.forEach(f -> keys.add(f.get("key").stringValue()));
            for (JsonNode field : scope.getValue()) {
                if (fields.size() < MAX_FIELDS && keys.add(field.get("key").stringValue())) {
                    fields.add(field);
                }
            }
        }
        return result;
    }

    private void options(JsonNode field, String label, ObjectNode clean) {
        JsonNode options = field.path("options");
        if (!options.isArray() || options.isEmpty()) {
            throw invalid("o campo \"%s\" precisa de pelo menos uma opção".formatted(label));
        }
        if (options.size() > MAX_OPTIONS) {
            throw invalid("no máximo %d opções por campo".formatted(MAX_OPTIONS));
        }
        ArrayNode cleanOptions = clean.putArray("options");
        Set<String> values = new HashSet<>();
        Set<String> labels = new HashSet<>();
        for (JsonNode option : options) {
            String optionLabel = label(option.path("label"), "Dê um nome a cada opção de \"" + label + "\".");
            if (!labels.add(optionLabel.toLowerCase(java.util.Locale.ROOT))) {
                throw invalid("a opção \"%s\" aparece duas vezes em \"%s\"".formatted(optionLabel, label));
            }
            String value = keyOf(option.path("value"), values, "o_");
            values.add(value);
            cleanOptions.addObject().put("value", value).put("label", optionLabel);
        }
        clean.put("allowCustom", field.path("allowCustom").asBoolean(false));
    }

    /** Escala de 1 a 5, com os polos opcionais ("Pacífico" ↔ "Violento"). */
    private static void scale(JsonNode field, ObjectNode clean) {
        clean.put("min", 1).put("max", 5);
        JsonNode poles = field.path("poles");
        if (poles.isArray() && poles.size() == 2) {
            String low = poles.get(0).asString("").strip();
            String high = poles.get(1).asString("").strip();
            if (!low.isEmpty() && !high.isEmpty()) {
                if (low.length() > MAX_LABEL || high.length() > MAX_LABEL) {
                    throw invalid("as pontas da escala têm no máximo %d caracteres".formatted(MAX_LABEL));
                }
                clean.putArray("poles").add(low).add(high);
            }
        }
    }

    private static String label(JsonNode node, String missing) {
        String text = node.isString() ? node.stringValue().strip() : "";
        if (text.isEmpty()) {
            throw invalid(missing);
        }
        if (text.length() > MAX_LABEL) {
            throw invalid("\"%s…\" passa de %d caracteres".formatted(text.substring(0, 20), MAX_LABEL));
        }
        return text;
    }

    /** Mantém a chave que veio (se for válida e única) ou gera uma nova. */
    private String keyOf(JsonNode node, Set<String> taken, String prefix) {
        String key = node.isString() ? node.stringValue() : "";
        if (KEY.matcher(key).matches() && !taken.contains(key)) {
            return key;
        }
        String generated;
        do {
            StringBuilder sb = new StringBuilder(prefix);
            for (int i = 0; i < 8; i++) {
                sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            generated = sb.toString();
        } while (taken.contains(generated));
        return generated;
    }

    static ApiException invalid(String reason) {
        return ApiException.unprocessable("custom_fields_invalid", "Campos próprios: " + reason + ".");
    }

    private static ApiException invalidValue(String key, String reason) {
        String where = key == null ? "" : " (" + key + ")";
        return ApiException.unprocessable("custom_value_invalid", "Campo próprio inválido" + where + ": " + reason + ".");
    }
}
