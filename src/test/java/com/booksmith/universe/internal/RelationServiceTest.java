package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.Test;

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

class RelationServiceTest {

    private final UniverseRepository universes = mock(UniverseRepository.class);
    private final UniverseEntityRepository entities = mock(UniverseEntityRepository.class);
    private final TimelineEventRepository events = mock(TimelineEventRepository.class);
    private final RelationRepository relations = mock(RelationRepository.class);
    private final RelationService service = new RelationService(universes, entities, events, relations,
            Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC));

    private final UUID owner = UUID.randomUUID();
    private final UUID universeId = UUID.randomUUID();
    private final List<UniverseEntity> characters = new ArrayList<>();
    private final List<Relation> stored = new ArrayList<>();
    private final UUID ana = character("Ana");
    private final UUID lia = character("Lia");

    RelationServiceTest() {
        when(universes.findByIdAndOwnerId(universeId, owner))
                .thenReturn(Optional.of(Universe.builder().id(universeId).ownerId(owner).name("U").build()));
        when(entities.findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(universeId, UniverseEntity.CHARACTER))
                .thenAnswer(i -> characters.stream().filter(c -> c.getDeletedAt() == null).toList());
        when(relations.findAllByUniverseIdAndDeletedAtIsNull(universeId))
                .thenAnswer(i -> stored.stream().filter(r -> r.getDeletedAt() == null).toList());
        when(relations.save(any())).thenAnswer(i -> {
            Relation r = i.getArgument(0);
            r.setId(UUID.randomUUID());
            stored.add(r);
            return r;
        });
    }

    @Test
    void motherAndDaughterIsADirectedRelation() {
        Relation relation = service.create(owner, universeId,
                new RelationService.RelationData(ana, lia, "parent", " Criou Lia sozinha. ", null, null));

        assertThat(relation.getDescription()).isEqualTo("Criou Lia sozinha.");
        assertThat(service.directed("parent")).isTrue();
        assertThat(service.directed("rival")).isFalse();
        // Lia também pode ser "mãe" de Ana em outra história? Não aqui: a direção oposta é outra relação
        service.create(owner, universeId, new RelationService.RelationData(lia, ana, "parent", null, null, null));
        assertThat(stored).hasSize(2);
    }

    @Test
    void symmetricRelationsDoNotRepeatInTheOtherDirection() {
        service.create(owner, universeId, new RelationService.RelationData(ana, lia, "rival", null, null, null));

        assertInvalid(() -> service.create(owner, universeId,
                new RelationService.RelationData(lia, ana, "rival", null, null, null)));
    }

    @Test
    void refusesSelfStrangersUnknownKindsAndEventsFromElsewhere() {
        assertInvalid(() -> service.create(owner, universeId,
                new RelationService.RelationData(ana, ana, "friend", null, null, null)));
        assertInvalid(() -> service.create(owner, universeId,
                new RelationService.RelationData(ana, UUID.randomUUID(), "friend", null, null, null)));
        assertInvalid(() -> service.create(owner, universeId,
                new RelationService.RelationData(ana, lia, "frenemy", null, null, null)));
        when(events.findByIdAndDeletedAtIsNull(any())).thenReturn(Optional.empty());
        assertInvalid(() -> service.create(owner, universeId,
                new RelationService.RelationData(ana, lia, "spouse", null, UUID.randomUUID(), null)));
    }

    @Test
    void relationsOfDeletedCharactersDisappear() {
        service.create(owner, universeId, new RelationService.RelationData(ana, lia, "friend", null, null, null));
        characters.getLast().setDeletedAt(Instant.now());

        assertThat(service.list(owner, universeId)).isEmpty();
    }

    private UUID character(String name) {
        UniverseEntity c = UniverseEntity.builder().id(UUID.randomUUID()).universeId(universeId)
                .kind(UniverseEntity.CHARACTER).name(name).build();
        characters.add(c);
        return c.getId();
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("relation_invalid"));
    }
}
