package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.media.api.ImageStorage;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UniverseTrashServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

    private final UniverseRepository universes = mock(UniverseRepository.class);
    private final UniverseEntityRepository entities = mock(UniverseEntityRepository.class);
    private final TimelineEventRepository events = mock(TimelineEventRepository.class);
    private final RelationRepository relations = mock(RelationRepository.class);
    private final UniverseImageRepository images = mock(UniverseImageRepository.class);
    private final ImageStorage storage = mock(ImageStorage.class);
    private final UniverseTrashService service = new UniverseTrashService(universes, entities, events, relations,
            images, storage, JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID owner = UUID.randomUUID();
    private final UUID universeId = UUID.randomUUID();

    UniverseTrashServiceTest() {
        Universe universe = Universe.builder().id(universeId).ownerId(owner).name("Terras de Vel").build();
        when(universes.findByIdAndOwnerId(universeId, owner)).thenReturn(Optional.of(universe));
        when(universes.findAllByOwnerIdOrderByNameAsc(owner)).thenReturn(List.of(universe));
    }

    @Test
    void listsWhatWasDeletedWithTheUniverseAndExpiry() {
        UniverseEntity lia = entity("Lia", UniverseEntity.CHARACTER, NOW.minusSeconds(3_600));
        when(entities.findTrashed(any(), any())).thenReturn(List.of(lia));
        when(events.findTrashed(any(), any())).thenReturn(List.of());
        when(relations.findTrashed(any(), any())).thenReturn(List.of());
        when(images.findTrashed(any(), any())).thenReturn(List.of());

        var items = service.list(owner);

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.type()).isEqualTo(UniverseTrashService.ItemType.CHARACTER);
            assertThat(item.universeName()).isEqualTo("Terras de Vel");
            assertThat(item.expiresAt()).isEqualTo(lia.getDeletedAt().plus(UniverseTrashService.RETENTION));
        });
    }

    @Test
    void placeComesBackLooseWhenItsParentIsGone() {
        UUID goneParent = UUID.randomUUID();
        UniverseEntity cafe = entity("Café Aurora", UniverseEntity.PLACE, NOW.minusSeconds(60));
        cafe.setAttributes("{\"type\":\"shop\",\"parentId\":\"" + goneParent + "\"}");
        when(entities.findById(cafe.getId())).thenReturn(Optional.of(cafe));
        when(entities.findByIdAndKindAndDeletedAtIsNull(goneParent, UniverseEntity.PLACE)).thenReturn(Optional.empty());

        service.restore(owner, UniverseTrashService.ItemType.PLACE, cafe.getId());

        assertThat(cafe.getDeletedAt()).isNull();
        assertThat(cafe.getAttributes()).isEqualTo("{\"type\":\"shop\"}");
    }

    @Test
    void relationWaitsForItsCharacters() {
        UniverseEntity ana = entity("Ana", UniverseEntity.CHARACTER, null);
        UniverseEntity lia = entity("Lia", UniverseEntity.CHARACTER, NOW.minusSeconds(60));
        Relation relation = Relation.builder().id(UUID.randomUUID()).universeId(universeId).fromId(ana.getId())
                .toId(lia.getId()).kind("parent").deletedAt(NOW.minusSeconds(60)).build();
        when(relations.findById(relation.getId())).thenReturn(Optional.of(relation));
        when(entities.findById(ana.getId())).thenReturn(Optional.of(ana));
        when(entities.findById(lia.getId())).thenReturn(Optional.of(lia));

        assertThatThrownBy(() -> service.restore(owner, UniverseTrashService.ItemType.RELATION, relation.getId()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("relation_needs_character");
                    assertThat(e.getMessage()).contains("Lia");
                });
        lia.setDeletedAt(null);
        service.restore(owner, UniverseTrashService.ItemType.RELATION, relation.getId());
        assertThat(relation.getDeletedAt()).isNull();
    }

    @Test
    void olderThanThirtyDaysIsGone() {
        UniverseEntity old = entity("Antigo", UniverseEntity.CHARACTER, NOW.minus(UniverseTrashService.RETENTION).minusSeconds(1));
        when(entities.findById(old.getId())).thenReturn(Optional.of(old));

        assertThatThrownBy(() -> service.restore(owner, UniverseTrashService.ItemType.CHARACTER, old.getId()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("trash_item_not_found"));
    }

    private UniverseEntity entity(String name, String kind, Instant deletedAt) {
        return UniverseEntity.builder().id(UUID.randomUUID()).universeId(universeId).kind(kind).name(name)
                .deletedAt(deletedAt).build();
    }

    @Test
    void purgeRemovesRelationsBeforeTheCharactersTheyPointTo() {
        service.purgeExpired();

        Instant cutoff = NOW.minus(UniverseTrashService.RETENTION);
        var order = inOrder(relations, entities, events);
        order.verify(relations).purgeDeletedBefore(cutoff);
        order.verify(relations).clearSinceBefore(cutoff);
        order.verify(relations).clearUntilBefore(cutoff);
        order.verify(entities).purgeDeletedBefore(cutoff);
        order.verify(events).purgeDeletedBefore(cutoff);
    }

    @Test
    void purgeAlsoRemovesTheImageFiles() {
        UniverseImage old = UniverseImage.builder().id(UUID.randomUUID()).universeId(universeId)
                .url("/media/gallery/x.webp").contentType("image/webp").deletedAt(NOW.minusSeconds(1)).build();
        when(images.findAllByDeletedAtBefore(NOW.minus(UniverseTrashService.RETENTION))).thenReturn(List.of(old));

        service.purgeExpired();

        verify(storage).delete("/media/gallery/x.webp");
        verify(images).deleteAll(List.of(old));
    }
}
