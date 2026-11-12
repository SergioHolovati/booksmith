package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.media.api.ImageStorage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UniverseServiceTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UniverseRepository universes = mock(UniverseRepository.class);
    private final UniverseEntityRepository entities = mock(UniverseEntityRepository.class);
    private final TimelineEventRepository timeline = mock(TimelineEventRepository.class);
    private final ImageStorage images = mock(ImageStorage.class);
    private final RelationRepository relations = mock(RelationRepository.class);
    private final UniverseImageRepository gallery = mock(UniverseImageRepository.class);
    private final UniverseService service = new UniverseService(universes, entities, timeline, relations,
            new AppearanceForm(mapper), new UniverseForm(mapper), images, gallery, mapper,
            new CustomFields(new LoreForm(mapper)),
            new UniverseBriefs(entities, relations, timeline, new UniverseForm(mapper), new LoreForm(mapper), mapper));

    private final UUID owner = UUID.randomUUID();
    private final UUID series = UUID.randomUUID();
    private final UUID sequel = UUID.randomUUID();

    @Test
    void mergeMovesEverythingAfterTheExistingTimelineAndReportsRepeatedNames() {
        owned(series);
        Universe universeSequel = owned(sequel);
        characters(series, "Lia", "Kael");
        characters(sequel, "lia", "Bruno");
        places(series, "Café Aurora");
        places(sequel, "Porto");
        when(timeline.maxSortKey(series)).thenReturn(new BigDecimal("3000"));
        when(timeline.minSortKey(sequel)).thenReturn(new BigDecimal("1000"));

        var result = service.merge(owner, sequel, series);

        assertThat(result.duplicateNames()).containsExactly("lia");
        // 1000 → 3001: o primeiro evento do livro novo vem logo depois do último da série
        verify(timeline).moveAll(sequel, series, new BigDecimal("2001"));
        verify(entities).moveAll(sequel, series);
        verify(relations).moveAll(sequel, series);
        verify(universes).delete(universeSequel);
    }

    @Test
    void mergeKeepsTheSeriesContentAndAddsTheOtherMagicLawsAndMap() {
        Universe target = owned(series);
        target.setDetails("{\"premise\":\"Memórias à venda\",\"magic\":[{\"id\":\"m1\",\"name\":\"Tecelagem\"}]}");
        Universe source = owned(sequel);
        source.setDetails("{\"premise\":\"Outra ideia\",\"sky\":\"Dois sóis\",\"magic\":[{\"id\":\"m1\",\"name\":\"Runas\"}],"
                + "\"laws\":[{\"id\":\"l1\",\"statement\":\"Ferro anula magia\"}]}");
        source.setMapUrl("http://media/maps/old.png");
        characters(series);
        characters(sequel);
        places(series);
        places(sequel);

        service.merge(owner, sequel, series);

        var details = mapper.readTree(target.getDetails());
        assertThat(details.get("premise").stringValue()).isEqualTo("Memórias à venda");
        assertThat(details.get("sky").stringValue()).isEqualTo("Dois sóis");
        assertThat(details.get("magic")).extracting(m -> m.get("name").stringValue()).containsExactly("Tecelagem", "Runas");
        assertThat(details.get("magic").get(1).get("id").stringValue()).isNotEqualTo("m1");
        assertThat(details.get("laws")).hasSize(1);
        assertThat(target.getMapUrl()).isEqualTo("http://media/maps/old.png");
    }

    @Test
    void staleVersionIsAConflict() {
        Universe universe = owned(series);
        universe.setVersion(4);

        assertThatThrownBy(() -> service.saveDetails(owner, series, 3, "Saga", mapper.readTree("{}")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("universe_version_conflict"));
    }

    @Test
    void mergeWithEmptyTimelineDoesNotShift() {
        owned(series);
        owned(sequel);
        characters(series);
        characters(sequel);
        places(series);
        places(sequel);

        service.merge(owner, sequel, series);

        verify(timeline).moveAll(sequel, series, BigDecimal.ZERO);
    }

    @Test
    void someoneElsesUniverseCannotBeJoined() {
        owned(sequel);
        when(universes.findByIdAndOwnerId(series, owner)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.merge(owner, sequel, series))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("universe_not_found"));
        verify(universes, never()).delete(any());
    }

    @Test
    void renameKeepsTheNameTidy() {
        Universe universe = owned(series);

        service.rename(owner, series, "  Crônicas do Café  ");

        assertThat(universe.getName()).isEqualTo("Crônicas do Café");
        assertThatThrownBy(() -> service.rename(owner, series, " "))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("universe_invalid"));
    }

    private Universe owned(UUID id) {
        Universe universe = Universe.builder().id(id).ownerId(owner).name("U").build();
        when(universes.findByIdAndOwnerId(id, owner)).thenReturn(Optional.of(universe));
        return universe;
    }

    private void characters(UUID universeId, String... names) {
        when(entities.findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(universeId, UniverseEntity.CHARACTER))
                .thenReturn(entitiesNamed(universeId, UniverseEntity.CHARACTER, names));
    }

    private void places(UUID universeId, String... names) {
        when(entities.findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(universeId, UniverseEntity.PLACE))
                .thenReturn(entitiesNamed(universeId, UniverseEntity.PLACE, names));
    }

    private static List<UniverseEntity> entitiesNamed(UUID universeId, String kind, String... names) {
        return java.util.Arrays.stream(names)
                .map(n -> UniverseEntity.builder().id(UUID.randomUUID()).universeId(universeId).kind(kind).name(n).build())
                .toList();
    }
}
