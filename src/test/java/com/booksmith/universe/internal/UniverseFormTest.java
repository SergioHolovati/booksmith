package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UniverseFormTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UniverseForm form = new UniverseForm(mapper);

    @Test
    void stepsFollowTheCreativeProcess() {
        assertThat(form.definition().get("tabs")).extracting(t -> t.get("label").stringValue())
                .containsExactly("Ideia", "Mundo", "Povos e culturas", "Mapa", "Magia", "Ciência", "Leis");
        assertThat(form.definition().get("collections").propertyNames()).containsExactly("magic", "laws");
    }

    @Test
    void describesTheUniverseInReadableLinesForTheAi() {
        JsonNode details = form.normalize(mapper.readTree("""
                {"premise": "Memórias à venda", "genres": ["fantasy", "Realismo mágico"], "techLevel": 3,
                 "magic": [{"name": "Tecelagem"}, {"name": "Runas"}],
                 "laws": [{"statement": "Ferro anula magia"}]}
                """), mapper);

        assertThat(form.describe(details, 600)).containsExactly(
                "Premissa: Memórias à venda",
                "Gêneros: Fantasia, Realismo mágico",
                "Sistema de magia: Tecelagem, Runas",
                "Tecnologia: Medieval",
                "Lei: Ferro anula magia");
        assertThat(form.describe(mapper.readTree("{\"premise\": \"abcdef\"}"), 3)).containsExactly("Premissa: abc…");
        assertThat(form.describe(null, 600)).isEmpty();
    }

    @Test
    void everythingIsOptional() {
        assertThat(form.normalize(mapper.readTree("{}"), mapper).isEmpty()).isTrue();
        assertThat(form.normalize(null, mapper).isEmpty()).isTrue();
    }

    @Test
    void keepsFieldsMagicAndLawsCleaned() {
        JsonNode saved = form.normalize(mapper.readTree("""
                {"premise": "  Memórias à venda  ", "genres": ["fantasy", "Realismo mágico"], "techLevel": 3,
                 "sky": "",
                 "magic": [{"id": "m1", "name": "Tecelagem", "hardness": 5, "cost": "Uma lembrança"},
                           {"name": "Runas", "effects": ["healing"]}],
                 "laws": [{"statement": "Ferro anula magia", "kind": "magic", "strictness": "absolute"}]}
                """), mapper);

        assertThat(saved.get("premise").stringValue()).isEqualTo("Memórias à venda");
        assertThat(saved.has("sky")).isFalse();
        assertThat(saved.get("magic").get(0).toString())
                .isEqualTo("{\"id\":\"m1\",\"name\":\"Tecelagem\",\"hardness\":5,\"cost\":\"Uma lembrança\"}");
        // item sem id ganha um, para o app e a continuidade se referirem a ele
        assertThat(saved.get("magic").get(1).get("id").stringValue()).hasSize(36);
        assertThat(form.count(saved, "laws")).isEqualTo(1);
    }

    @Test
    void rejectsUnknownFieldsAndOptionsInsideItems() {
        assertInvalid("{\"flavor\": \"x\"}", "flavor");
        assertInvalid("{\"magic\": [{\"color\": \"azul\"}]}", "magic.color");
        assertInvalid("{\"laws\": [{\"kind\": \"cosmic\"}]}", "laws.kind");
        assertInvalid("{\"magic\": {\"name\": \"x\"}}", "magic");
        assertInvalid("{\"techLevel\": 9}", "techLevel");
    }

    @Test
    void repeatedItemIdsAreReplaced() {
        JsonNode saved = form.normalize(mapper.readTree(
                "{\"laws\": [{\"id\": \"a\", \"statement\": \"Um\"}, {\"id\": \"a\", \"statement\": \"Dois\"}]}"), mapper);
        assertThat(saved.get("laws").get(0).get("id").stringValue()).isEqualTo("a");
        assertThat(saved.get("laws").get(1).get("id").stringValue()).isNotEqualTo("a");
    }

    private void assertInvalid(String details, String where) {
        assertThatThrownBy(() -> form.normalize(mapper.readTree(details), mapper))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("universe_invalid");
                    assertThat(e.getMessage()).contains(where);
                });
    }
}
