package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CharacterServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UniverseRepository universes = mock(UniverseRepository.class);
    private final UniverseEntityRepository entities = mock(UniverseEntityRepository.class);
    private final CharacterService service = new CharacterService(universes, entities, new AppearanceForm(mapper),
            mapper, Clock.fixed(NOW, ZoneOffset.UTC),
            new CustomFieldService(universes, new CustomFields(new LoreForm(mapper)), mapper));

    private final UUID owner = UUID.randomUUID();
    private final UUID universeId = UUID.randomUUID();

    @Test
    void savesSheetAndAppearanceNormalized() {
        UniverseEntity lia = character(3);
        when(entities.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        var saved = service.update(owner, lia.getId(), 3, new CharacterService.CharacterData(
                "  Lia  ", List.of("Li", " ", "Li", "Liazinha"),
                mapper.readTree("{\"role\": \"protagonist\", \"age\": \" 17 \", \"fears\": \"\"}"),
                mapper.readTree("{\"eyeColor\": \"green\"}")));

        assertThat(saved.getName()).isEqualTo("Lia");
        assertThat(saved.getAliases()).containsExactly("Li", "Liazinha");
        assertThat(saved.getAttributes()).isEqualTo("{\"role\":\"protagonist\",\"age\":\"17\"}");
        assertThat(saved.getAppearance()).isEqualTo("{\"eyeColor\":\"green\"}");
        assertThat(service.appearanceSummary(saved)).isEqualTo("Olhos verdes");
    }

    @Test
    void staleVersionIsAConflict() {
        UniverseEntity lia = character(5);

        assertThatThrownBy(() -> service.update(owner, lia.getId(), 4,
                new CharacterService.CharacterData("Lia", List.of(), null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("character_version_conflict"));
    }

    @Test
    void unknownSheetFieldAndRoleAreRejected() {
        UniverseEntity lia = character(0);

        assertThatThrownBy(() -> service.update(owner, lia.getId(), 0, new CharacterService.CharacterData(
                "Lia", List.of(), mapper.readTree("{\"role\": \"vilã\"}"), null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("character_invalid"));
        assertThatThrownBy(() -> service.update(owner, lia.getId(), 0, new CharacterService.CharacterData(
                "Lia", List.of(), mapper.readTree("{\"shoeSize\": \"38\"}"), null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("character_invalid"));
    }

    @Test
    void someoneElsesCharacterDoesNotExist() {
        UniverseEntity lia = character(0);
        UUID stranger = UUID.randomUUID();
        when(universes.findByIdAndOwnerId(universeId, stranger)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(stranger, lia.getId()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("character_not_found"));
    }

    @Test
    void deleteMarksTheCharacter() {
        UniverseEntity lia = character(0);

        service.delete(owner, lia.getId());

        assertThat(lia.getDeletedAt()).isEqualTo(NOW);
    }

    private UniverseEntity character(long version) {
        UniverseEntity character = UniverseEntity.builder()
                .id(UUID.randomUUID())
                .universeId(universeId)
                .kind(UniverseEntity.CHARACTER)
                .name("Lia")
                .version(version)
                .build();
        when(entities.findByIdAndKindAndDeletedAtIsNull(character.getId(), UniverseEntity.CHARACTER))
                .thenReturn(Optional.of(character));
        when(universes.findByIdAndOwnerId(universeId, owner))
                .thenReturn(Optional.of(Universe.builder().id(universeId).ownerId(owner).name("A Conversão").build()));
        return character;
    }
}
