package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoreServiceTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UniverseRepository universes = mock(UniverseRepository.class);
    private final UniverseEntityRepository entities = mock(UniverseEntityRepository.class);
    private final LoreService service = new LoreService(universes, entities, new LoreForm(mapper), mapper,
            Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC));

    private final UUID owner = UUID.randomUUID();
    private final UUID universeId = UUID.randomUUID();
    private final List<UniverseEntity> places = new ArrayList<>();
    private final List<UniverseEntity> lore = new ArrayList<>();

    LoreServiceTest() {
        when(universes.findByIdAndOwnerId(universeId, owner))
                .thenReturn(Optional.of(Universe.builder().id(universeId).ownerId(owner).name("U").build()));
        when(entities.findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(universeId, UniverseEntity.PLACE))
                .thenAnswer(i -> places);
        when(entities.findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(universeId, UniverseEntity.LORE))
                .thenAnswer(i -> lore.stream().filter(e -> e.getDeletedAt() == null).toList());
        when(entities.save(any())).thenAnswer(i -> {
            UniverseEntity saved = i.getArgument(0);
            saved.setId(UUID.randomUUID());
            lore.add(saved);
            return saved;
        });
        when(entities.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void definitionHasTheSevenKindsOfWorldContext() {
        LoreForm form = new LoreForm(mapper);
        assertThat(form.types()).containsExactly(
                "religion", "government", "language", "organization", "creature", "object", "term");
        assertThat(form.summaryField("term")).isEqualTo("meaning");
    }

    @Test
    void religionIsLinkedToPlacesAndItsSheetIsValidated() {
        UniverseEntity valdfjord = place("Valdfjord");
        UniverseEntity ostmar = place("Ostmar");
        UniverseEntity faith = service.create(owner, universeId, "religion", "Culto do Sol");
        when(entities.findByIdAndKindAndDeletedAtIsNull(faith.getId(), UniverseEntity.LORE)).thenReturn(Optional.of(faith));

        var saved = service.update(owner, faith.getId(), 0, new LoreService.LoreData("Culto do Sol", List.of(),
                mapper.readTree("{\"kind\": \"polytheist\", \"beliefs\": \" O sol renasce. \", \"influence\": 4}"),
                List.of(valdfjord.getId(), ostmar.getId(), valdfjord.getId())));

        assertThat(service.typeOf(saved)).isEqualTo("religion");
        assertThat(service.placeIds(saved)).containsExactly(valdfjord.getId(), ostmar.getId());
        assertThat(service.summary(saved)).isEqualTo("O sol renasce.");
        assertThat(service.atPlace(owner, universeId, ostmar.getId())).containsExactly(saved);

        assertInvalid(() -> service.update(owner, faith.getId(), 0, new LoreService.LoreData("Culto", List.of(),
                mapper.readTree("{\"form\": \"monarchy\"}"), List.of())));
        assertInvalid(() -> service.update(owner, faith.getId(), 0, new LoreService.LoreData("Culto", List.of(),
                mapper.readTree("{}"), List.of(UUID.randomUUID()))));
    }

    @Test
    void unknownTypeIsRefused() {
        assertInvalid(() -> service.create(owner, universeId, "dragon", "Smaug"));
    }

    @Test
    void batchFindsPlacesByNameAndDoesNotDuplicate() {
        UniverseEntity valdfjord = place("Valdfjord");
        var first = service.importBatch(owner, universeId, List.of(
                new LoreService.LoreDraft("government", "Coroa de Valdfjord", mapper.readTree("{\"form\": \"monarchy\"}"),
                        List.of("valdfjord", "Lugar que não existe")),
                new LoreService.LoreDraft("language", "Valdês", null, List.of("Valdfjord"))));
        var second = service.importBatch(owner, universeId, List.of(
                new LoreService.LoreDraft("government", "coroa de valdfjord", null, List.of())));

        assertThat(first.created()).isEqualTo(2);
        assertThat(service.placeIds(lore.getFirst())).containsExactly(valdfjord.getId());
        assertThat(second.created()).isZero();
        assertThat(second.skipped()).containsExactly("coroa de valdfjord");
    }

    private UniverseEntity place(String name) {
        UniverseEntity place = UniverseEntity.builder().id(UUID.randomUUID()).universeId(universeId)
                .kind(UniverseEntity.PLACE).name(name).build();
        places.add(place);
        return place;
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("lore_invalid"));
    }
}
