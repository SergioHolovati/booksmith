package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppearanceFormTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AppearanceForm form = new AppearanceForm(mapper);

    @Test
    void definitionHasTheTabsOfTheProductPlan() {
        assertThat(form.definition().get("tabs")).extracting(tab -> tab.get("label").stringValue())
                .containsExactly("Base", "Rosto", "Pele", "Cabelo e pelos", "Corpo", "Roupas e acessórios",
                        "Presença", "Referências", "Livre", "Temperamento", "Qualidades e defeitos", "Habilidades",
                        "Voz e fala", "O que importa");
        for (JsonNode tab : form.definition().get("tabs")) {
            assertThat(tab.get("group").stringValue()).isIn("appearance", "personality");
        }
    }

    /** A v2 só acrescenta: tudo o que foi salvo com a v1 continua válido. */
    @Test
    void everyFieldOfVersionOneIsStillThere() {
        assertThat(AppearanceForm.VERSION).isEqualTo(2);
        Set<String> keys = new HashSet<>();
        form.definition().get("tabs").forEach(tab -> tab.get("fields").forEach(f -> keys.add(f.get("key").stringValue())));
        assertThat(keys).contains("species", "apparentAge", "gender", "height", "build", "eyeColor", "eyeShape",
                "faceShape", "jaw", "eyebrows", "nose", "lips", "ears", "skinTone", "undertone", "skinTexture", "marks",
                "marksDetail", "hairColor", "hairTexture", "hairLength", "hairStyle", "hairSecondary", "facialHair",
                "posture", "physical", "fantasy", "clothingStyle", "outfit", "colors", "accessories", "signatureItem",
                "notes");
        var saved = form.normalize(mapper.readTree("{\"eyeColor\": \"green\", \"height\": 4, \"marks\": [\"freckles\"]}"),
                mapper);
        assertThat(saved.size()).isEqualTo(3);
    }

    @Test
    void personalitySlidersAndListsAreValidated() {
        var saved = form.normalize(mapper.readTree(
                "{\"introExtro\": 1, \"virtues\": [\"loyal\", \"Teimosamente gentil\"], \"catchphrase\": \" Não é da sua conta. \"}"),
                mapper);
        assertThat(saved.toString()).isEqualTo(
                "{\"introExtro\":1,\"virtues\":[\"loyal\",\"Teimosamente gentil\"],\"catchphrase\":\"Não é da sua conta.\"}");
        assertInvalid("{\"introExtro\": 6}", "introExtro");
        // personalidade não entra no resumo de aparência usado nas listas
        assertThat(form.summary(saved)).isEmpty();
    }

    @Test
    void everyOptionValueIsUniqueWithinItsFieldAndEveryFieldHasAKnownType() {
        Set<String> types = Set.of("choice", "multi", "color", "scale", "text", "longtext");
        for (JsonNode tab : form.definition().get("tabs")) {
            for (JsonNode field : tab.get("fields")) {
                assertThat(types).contains(field.get("type").stringValue());
                if (field.has("options")) {
                    Set<String> values = new HashSet<>();
                    for (JsonNode option : field.get("options")) {
                        assertThat(values.add(option.get("value").stringValue()))
                                .as("opção repetida em %s", field.get("key")).isTrue();
                    }
                }
            }
        }
    }

    @Test
    void keepsValidValuesAndDropsEmptyOnes() {
        JsonNode values = json("""
                {"eyeColor": "green", "hairColor": "  ruivo-fogo  ", "height": 4, "marks": ["freckles", "freckles", "Marca de queimadura"],
                 "hairStyle": "  ", "notes": null, "outfit": "Casaco verde"}
                """);

        JsonNode result = form.normalize(values, mapper);

        assertThat(result.toString()).isEqualTo(
                "{\"eyeColor\":\"green\",\"hairColor\":\"ruivo-fogo\",\"height\":4,"
                        + "\"marks\":[\"freckles\",\"Marca de queimadura\"],\"outfit\":\"Casaco verde\"}");
    }

    @Test
    void rejectsUnknownFieldsOptionsAndRanges() {
        assertInvalid("{\"tailLength\": \"long\"}", "tailLength");
        assertInvalid("{\"eyeShape\": \"triangular\"}", "eyeShape"); // sem allowCustom
        assertInvalid("{\"height\": 9}", "height");
        assertInvalid("{\"height\": \"alta\"}", "height");
        assertInvalid("{\"marks\": \"freckles\"}", "marks");
        assertInvalid("{\"eyeColor\": \"" + "x".repeat(81) + "\"}", "eyeColor");
    }

    @Test
    void summarizesTheMainTraitsInReadingOrder() {
        JsonNode values = json("""
                {"species": "elf", "eyeColor": "green", "skinTone": "tan", "hairColor": "red", "hairTexture": "curly",
                 "height": 5}
                """);

        assertThat(form.summary(values)).isEqualTo("Elfo, olhos verdes, pele morena, cabelo ruivo, cacheado");
    }

    @Test
    void summarySkipsHumansAndKeepsCustomTextAsWritten() {
        assertThat(form.summary(json("{\"species\": \"human\", \"eyeColor\": \"cor de âmbar\"}")))
                .isEqualTo("Olhos cor de âmbar");
        assertThat(form.summary(json("{}"))).isEmpty();
    }

    private void assertInvalid(String values, String field) {
        assertThatThrownBy(() -> form.normalize(json(values), mapper))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("appearance_invalid");
                    assertThat(e.getMessage()).contains(field);
                });
    }

    private JsonNode json(String value) {
        return mapper.readTree(value);
    }
}
