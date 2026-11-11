package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomFieldsTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final CustomFields fields = new CustomFields(new LoreForm(mapper));

    @Test
    void scopesAreCharacterPlaceAndEveryWorldSheet() {
        assertThat(fields.scopes()).startsWith("character", "place").contains("religion", "creature", "term");
        assertThatThrownBy(() -> fields.requireScope("book")).isInstanceOf(ApiException.class);
    }

    @Test
    void keysAndOptionValuesAreGeneratedOnceAndKept() {
        JsonNode first = fields.define(json("""
                [{"label": " Signo ", "type": "choice", "options": [{"label": "Áries"}, {"label": "Touro"}]},
                 {"label": "Bordão", "type": "text"}]"""), mapper);
        String key = first.get(0).get("key").stringValue();
        String aries = first.get(0).get("options").get(0).get("value").stringValue();
        assertThat(key).startsWith("f_").hasSize(10);
        assertThat(aries).startsWith("o_");
        assertThat(first.get(0).get("label").stringValue()).isEqualTo("Signo");
        assertThat(first.get(0).get("allowCustom").booleanValue()).isFalse();

        // Renomear o campo e a opção mantém as chaves: o que já foi preenchido continua valendo
        JsonNode renamed = fields.define(json("""
                [{"key": "%s", "label": "Signo do zodíaco", "type": "choice",
                  "options": [{"value": "%s", "label": "Carneiro"}]}]""".formatted(key, aries)), mapper);
        assertThat(renamed.get(0).get("key").stringValue()).isEqualTo(key);
        assertThat(renamed.get(0).get("options").get(0).get("value").stringValue()).isEqualTo(aries);
    }

    @Test
    void scaleIsOneToFiveWithOptionalPoles() {
        JsonNode scale = fields.define(json("""
                [{"label": "Temperamento", "type": "scale", "poles": ["Calmo", "Explosivo"]}]"""), mapper);
        assertThat(scale.get(0).get("min").intValue()).isEqualTo(1);
        assertThat(scale.get(0).get("max").intValue()).isEqualTo(5);
        assertThat(scale.get(0).get("poles").get(1).stringValue()).isEqualTo("Explosivo");
    }

    @Test
    void badDefinitionsAreRejected() {
        assertInvalid("[{\"label\": \"\", \"type\": \"text\"}]");
        assertInvalid("[{\"label\": \"Cor\", \"type\": \"color\"}]");
        assertInvalid("[{\"label\": \"Signo\", \"type\": \"choice\", \"options\": []}]");
        assertInvalid("[{\"label\": \"Signo\", \"type\": \"choice\", \"options\": [{\"label\": \"Áries\"}, {\"label\": \"áries\"}]}]");
        assertInvalid("{\"label\": \"Signo\"}");
        assertInvalid("[" + "{\"label\": \"x\", \"type\": \"text\"},".repeat(CustomFields.MAX_FIELDS) + "{\"label\": \"y\", \"type\": \"text\"}]");
    }

    @Test
    void valuesFromDeletedFieldsAreDroppedAndWrongValuesRejected() {
        JsonNode definition = json("""
                {"character": [{"key": "f_signo", "label": "Signo", "type": "choice", "allowCustom": false,
                                "options": [{"value": "o_aries", "label": "Áries"}]},
                               {"key": "f_forca", "label": "Força", "type": "scale", "min": 1, "max": 5}]}""");
        var clean = fields.values(definition, "character",
                json("{\"f_signo\": \"o_aries\", \"f_forca\": 4, \"f_apagado\": \"sobra\"}"), mapper);
        assertThat(clean.toString()).isEqualTo("{\"f_signo\":\"o_aries\",\"f_forca\":4}");

        assertThatThrownBy(() -> fields.values(definition, "character", json("{\"f_forca\": 9}"), mapper))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("custom_value_invalid"));
        assertThatThrownBy(() -> fields.values(definition, "character", json("{\"f_signo\": \"Leão\"}"), mapper))
                .isInstanceOf(ApiException.class);
        // Os campos de um tipo não valem em outro
        assertThat(fields.values(definition, "place", json("{\"f_signo\": \"o_aries\"}"), mapper).isEmpty()).isTrue();
    }

    @Test
    void staleValuesStayStoredButAreNotShown() {
        // "Força" era texto e virou escala; "Signo" perdeu a opção escolhida
        JsonNode definition = json("""
                {"character": [{"key": "f_signo", "label": "Signo", "type": "choice", "allowCustom": false,
                                "options": [{"value": "o_touro", "label": "Touro"}]},
                               {"key": "f_forca", "label": "Força", "type": "scale", "min": 1, "max": 5},
                               {"key": "f_bordao", "label": "Bordão", "type": "text"}]}""");
        JsonNode stored = json("{\"f_signo\": \"o_aries\", \"f_forca\": \"muita\", \"f_bordao\": \"Vamos!\", \"f_x\": 1}");
        assertThat(fields.current(definition, "character", stored, mapper).toString())
                .isEqualTo("{\"f_bordao\":\"Vamos!\"}");
    }

    @Test
    void mergeBringsTheFieldsTheTargetDoesNotHave() {
        JsonNode target = json("{\"character\": [{\"key\": \"f_a\", \"label\": \"A\", \"type\": \"text\"}]}");
        JsonNode source = json("""
                {"character": [{"key": "f_a", "label": "A de lá", "type": "text"},
                               {"key": "f_b", "label": "B", "type": "text"}],
                 "place": [{"key": "f_c", "label": "C", "type": "longtext"}]}""");
        JsonNode merged = fields.merge(target, source, mapper);
        assertThat(merged.get("character")).hasSize(2);
        assertThat(merged.get("character").get(0).get("label").stringValue()).isEqualTo("A");
        assertThat(merged.get("place").get(0).get("key").stringValue()).isEqualTo("f_c");
    }

    private void assertInvalid(String definition) {
        assertThatThrownBy(() -> fields.define(json(definition), mapper))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("custom_fields_invalid"));
    }

    private JsonNode json(String text) {
        return mapper.readTree(text);
    }
}
