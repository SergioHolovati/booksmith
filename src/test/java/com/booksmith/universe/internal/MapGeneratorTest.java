package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MapGeneratorTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void keepsSeedAndSettings() {
        var saved = MapGenerator.normalize(mapper.readTree("""
                {"version": 1, "seed": " vel-42 ", "size": "medium", "land": "much", "countries": 5,
                 "climate": "varied", "style": "parchment"}"""), mapper);
        assertThat(saved.get("seed").stringValue()).isEqualTo("vel-42");
        assertThat(saved.get("countries").intValue()).isEqualTo(5);
    }

    @Test
    void rejectsUnknownSettingsAndValues() {
        assertInvalid("{\"version\": 1, \"seed\": \"a\", \"rivers\": 3}");
        assertInvalid("{\"version\": 1, \"seed\": \"a\", \"climate\": \"lava\"}");
        assertInvalid("{\"version\": 1, \"seed\": \"a\", \"countries\": 40}");
        assertInvalid("{\"seed\": \"a\"}");
        assertInvalid("[]");
    }

    private void assertInvalid(String json) {
        assertThatThrownBy(() -> MapGenerator.normalize(mapper.readTree(json), mapper))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("map_generator_invalid"));
    }
}
