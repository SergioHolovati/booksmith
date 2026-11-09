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

class PlaceServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UniverseRepository universes = mock(UniverseRepository.class);
    private final UniverseEntityRepository entities = mock(UniverseEntityRepository.class);
    private final PlaceService service = new PlaceService(universes, entities, mapper, Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID owner = UUID.randomUUID();
    private final UUID universeId = UUID.randomUUID();
    private final List<UniverseEntity> places = new ArrayList<>();

    PlaceServiceTest() {
        when(universes.findByIdAndOwnerId(universeId, owner))
                .thenReturn(Optional.of(Universe.builder().id(universeId).ownerId(owner).name("U").build()));
        when(entities.findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(universeId, UniverseEntity.PLACE))
                .thenAnswer(i -> places.stream().filter(p -> p.getDeletedAt() == null).toList());
        when(entities.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void savesSheetWithParentInTheSameUniverse() {
        UniverseEntity city = place("São Paulo", null);
        UniverseEntity cafe = place("Café da Esquina", null);

        var saved = service.update(owner, cafe.getId(), 0, data("Café da Esquina",
                "{\"type\": \"shop\", \"parentId\": \"%s\", \"atmosphere\": \" Cheiro de canela \"}".formatted(city.getId())));

        assertThat(saved.getAttributes()).contains("\"type\":\"shop\"").contains("\"atmosphere\":\"Cheiro de canela\"");
        assertThat(service.parentOf(saved)).contains(city.getId());
    }

    @Test
    void refusesCyclesAndStrangers() {
        UniverseEntity city = place("Cidade", null);
        UniverseEntity district = place("Bairro", city.getId());

        // A cidade não pode ficar dentro do próprio bairro
        assertInvalid(city, "{\"parentId\": \"%s\"}".formatted(district.getId()));
        // Nem dentro de si mesma
        assertInvalid(city, "{\"parentId\": \"%s\"}".formatted(city.getId()));
        // Nem dentro de um lugar de outro universo
        assertInvalid(city, "{\"parentId\": \"%s\"}".formatted(UUID.randomUUID()));
        assertInvalid(city, "{\"type\": \"spaceship\"}");
        assertInvalid(city, "{\"parentId\": \"não é um id\"}");
    }

    @Test
    void deletingAPlaceMovesWhatWasInsideOneLevelUp() {
        UniverseEntity country = place("País", null);
        UniverseEntity city = place("Cidade", country.getId());
        UniverseEntity district = place("Bairro", city.getId());
        UniverseEntity loose = place("Ilha", city.getId());

        service.delete(owner, city.getId());

        assertThat(city.getDeletedAt()).isEqualTo(NOW);
        assertThat(service.parentOf(district)).contains(country.getId());
        assertThat(service.parentOf(loose)).contains(country.getId());

        service.delete(owner, country.getId());
        assertThat(service.parentOf(district)).isEmpty();
    }

    @Test
    void someoneElsesPlaceDoesNotExist() {
        UniverseEntity city = place("Cidade", null);
        UUID stranger = UUID.randomUUID();

        assertThatThrownBy(() -> service.get(stranger, city.getId()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("place_not_found"));
    }

    @Test
    void importsMapPlacesWithHierarchyAndPositionWithoutDuplicating() {
        place("Ostmar", null); // já existia: não é criado de novo, mas serve de "dentro de"
        when(entities.save(any())).thenAnswer(i -> {
            UniverseEntity saved = i.getArgument(0);
            saved.setId(UUID.randomUUID());
            places.add(saved);
            return saved;
        });

        var result = service.importFromMap(owner, universeId, List.of(
                new PlaceService.MapPlace("c0", "Valdfjord", "realm", null, 0.4, 0.3, "Capital: Ostmar.", null),
                new PlaceService.MapPlace("s0", "ostmar", "city", "c0", 0.41, 0.32, "Capital de Valdfjord.", null),
                new PlaceService.MapPlace("s1", "Vila Cinza", "town", "s0", 0.45, 0.35, null, "Frio e úmido.")));

        assertThat(result.created()).isEqualTo(2);
        assertThat(result.skipped()).containsExactly("ostmar");
        UniverseEntity realm = places.stream().filter(p -> p.getName().equals("Valdfjord")).findFirst().orElseThrow();
        UniverseEntity village = places.stream().filter(p -> p.getName().equals("Vila Cinza")).findFirst().orElseThrow();
        UniverseEntity ostmar = places.getFirst();
        assertThat(realm.getMapX()).isEqualTo(0.4f);
        assertThat(realm.getAttributes()).isEqualTo("{\"type\":\"realm\",\"description\":\"Capital: Ostmar.\"}");
        assertThat(village.getAttributes()).contains("\"parentId\":\"" + ostmar.getId() + "\"");
    }

    @Test
    void mapPlacesOutsideTheMapOrWithUnknownParentAreRefused() {
        assertThatThrownBy(() -> service.importFromMap(owner, universeId,
                List.of(new PlaceService.MapPlace("a", "Fora", "city", null, 1.5, 0.2, null, null))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("place_invalid"));
        assertThatThrownBy(() -> service.importFromMap(owner, universeId,
                List.of(new PlaceService.MapPlace("a", "Órfã", "city", "z", 0.5, 0.5, null, null))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("place_invalid"));
    }

    private void assertInvalid(UniverseEntity place, String sheet) {
        assertThatThrownBy(() -> service.update(owner, place.getId(), place.getVersion(), data(place.getName(), sheet)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("place_invalid"));
    }

    private PlaceService.PlaceData data(String name, String sheet) {
        return new PlaceService.PlaceData(name, List.of(), mapper.readTree(sheet));
    }

    private UniverseEntity place(String name, UUID parentId) {
        UniverseEntity place = UniverseEntity.builder()
                .id(UUID.randomUUID())
                .universeId(universeId)
                .kind(UniverseEntity.PLACE)
                .name(name)
                .attributes(parentId == null ? "{}" : "{\"parentId\":\"%s\"}".formatted(parentId))
                .build();
        places.add(place);
        when(entities.findByIdAndKindAndDeletedAtIsNull(place.getId(), UniverseEntity.PLACE))
                .thenAnswer(i -> place.getDeletedAt() == null ? Optional.of(place) : Optional.empty());
        return place;
    }
}
