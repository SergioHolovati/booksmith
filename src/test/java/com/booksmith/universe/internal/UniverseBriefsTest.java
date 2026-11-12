package com.booksmith.universe.internal;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UniverseBriefsTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UniverseEntityRepository entities = mock(UniverseEntityRepository.class);
    private final RelationRepository relations = mock(RelationRepository.class);
    private final TimelineEventRepository timeline = mock(TimelineEventRepository.class);
    private final UniverseBriefs briefs = new UniverseBriefs(entities, relations, timeline, new UniverseForm(mapper),
            new LoreForm(mapper), mapper);

    private final UUID universeId = UUID.randomUUID();
    private final Universe universe = Universe.builder().id(universeId).name("Vel").build();

    private final UniverseEntity lia = entity(UniverseEntity.CHARACTER, "Lia",
            "{\"role\": \"protagonist\", \"occupation\": \"barista\", \"personality\": \"curiosa e teimosa\"}", "{}");
    private final UniverseEntity tomas = entity(UniverseEntity.CHARACTER, "Tomás",
            "{\"role\": \"antagonist\", \"desires\": \"comprar o café\"}", "{}");
    private final UniverseEntity cafe = entity(UniverseEntity.PLACE, "Café do Porto",
            "{\"type\": \"building\", \"description\": \"Guarda memórias nas xícaras.\", \"rules\": \"Ninguém mente lá dentro.\"}", "{}");
    private final UniverseEntity mare = entity(UniverseEntity.LORE, "A Maré",
            "{\"type\": \"religion\"}", "{\"beliefs\": \"Os mortos voltam com a maré.\"}");

    UniverseBriefsTest() {
        when(entities.findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(universeId, UniverseEntity.CHARACTER))
                .thenReturn(List.of(lia, tomas));
        when(entities.findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(universeId, UniverseEntity.PLACE))
                .thenReturn(List.of(cafe));
        when(entities.findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(universeId, UniverseEntity.LORE))
                .thenReturn(List.of(mare));
        when(relations.findAllByUniverseIdAndDeletedAtIsNull(universeId)).thenReturn(List.of(
                Relation.builder().fromId(tomas.getId()).toId(cafe.getId()).kind("other").build(),
                Relation.builder().fromId(lia.getId()).toId(tomas.getId()).kind("sibling")
                        .description("Brigaram depois do\nincêndio.").build()));
        when(timeline.findAllByUniverseIdAndDeletedAtIsNullOrderBySortKeyAsc(universeId)).thenReturn(List.of(
                TimelineEvent.builder().title("A grande enchente").whenLabel("Ano 312").sortKey(BigDecimal.ONE)
                        .description("A cidade baixa ficou submersa.").characterIds(new UUID[]{lia.getId()}).build()));
    }

    @Test
    void tellsTheAiWhoIsWhoWhereAndWhatHappened() {
        var brief = briefs.of(universe, mapper.readTree("{\"premise\": \"Um café que guarda memórias\"}"), null);

        assertThat(brief.name()).isEqualTo("Vel");
        assertThat(brief.lines()).containsExactly("Premissa: Um café que guarda memórias");
        assertThat(brief.characters()).containsExactly(
                "Lia (protagonista) — barista; jeito: curiosa e teimosa",
                "Tomás (antagonista) — quer: comprar o café");
        assertThat(brief.places()).containsExactly(
                "Café do Porto (prédio): Guarda memórias nas xícaras. Regras: Ninguém mente lá dentro.");
        assertThat(brief.relations()).containsExactly(
                "Tomás tem ligação com Café do Porto",
                "Lia é irmã/irmão de Tomás: Brigaram depois do incêndio.");
        assertThat(brief.lore()).containsExactly("Religiões: A Maré (Os mortos voltam com a maré.)");
        assertThat(brief.timeline()).containsExactly("Ano 312 — A grande enchente: A cidade baixa ficou submersa. (Lia)");
    }

    @Test
    void theCharacterInFocusLeavesTheListAndTheirRelationsComeFirst() {
        var brief = briefs.of(universe, mapper.readTree("{}"), lia.getId());

        assertThat(brief.characters()).containsExactly("Tomás (antagonista) — quer: comprar o café");
        assertThat(brief.relations().getFirst()).startsWith("Lia é irmã/irmão de Tomás");
    }

    @Test
    void longTextsAreCutToOneShortLine() {
        assertThat(UniverseBriefs.cut("a\n\nb   c")).isEqualTo("a b c");
        assertThat(UniverseBriefs.cut("x".repeat(500))).hasSize(UniverseBriefs.SNIPPET).endsWith("…");
    }

    private UniverseEntity entity(String kind, String name, String attributes, String appearance) {
        return UniverseEntity.builder().id(UUID.randomUUID()).universeId(universeId).kind(kind).name(name)
                .attributes(attributes).appearance(appearance).build();
    }
}
