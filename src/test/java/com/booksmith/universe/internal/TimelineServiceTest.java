package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TimelineServiceTest {

    private final UniverseRepository universes = mock(UniverseRepository.class);
    private final UniverseEntityRepository entities = mock(UniverseEntityRepository.class);
    private final TimelineEventRepository events = mock(TimelineEventRepository.class);
    private final TimelineService service = new TimelineService(universes, entities, events,
            Clock.fixed(Instant.parse("2026-11-08T12:00:00Z"), ZoneOffset.UTC));

    private final UUID owner = UUID.randomUUID();
    private final UUID universeId = UUID.randomUUID();
    private final List<TimelineEvent> stored = new ArrayList<>();

    TimelineServiceTest() {
        when(universes.findByIdAndOwnerId(universeId, owner))
                .thenReturn(Optional.of(Universe.builder().id(universeId).ownerId(owner).name("U").build()));
        when(events.findAllByUniverseIdAndDeletedAtIsNullOrderBySortKeyAsc(universeId)).thenAnswer(i -> stored.stream()
                .filter(e -> e.getDeletedAt() == null)
                .sorted(Comparator.comparing(TimelineEvent::getSortKey))
                .toList());
        when(events.save(any())).thenAnswer(i -> {
            TimelineEvent event = i.getArgument(0);
            event.setId(UUID.randomUUID());
            stored.add(event);
            when(events.findByIdAndDeletedAtIsNull(event.getId())).thenReturn(Optional.of(event));
            return event;
        });
        when(events.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void newEventsGoToTheEndOrRightAfterTheChosenOne() {
        TimelineEvent first = service.create(owner, universeId, "Lia chega à cidade", null);
        TimelineEvent last = service.create(owner, universeId, "O bilhete", null);
        TimelineEvent middle = service.create(owner, universeId, "O café", first.getId());

        assertThat(titles()).containsExactly("Lia chega à cidade", "O café", "O bilhete");
        assertThat(middle.getSortKey()).isBetween(first.getSortKey(), last.getSortKey());
    }

    @Test
    void moveToTheBeginningAndAfterAnother() {
        TimelineEvent a = service.create(owner, universeId, "A", null);
        TimelineEvent b = service.create(owner, universeId, "B", null);
        TimelineEvent c = service.create(owner, universeId, "C", null);

        service.move(owner, c.getId(), null);
        assertThat(titles()).containsExactly("C", "A", "B");

        service.move(owner, c.getId(), a.getId());
        assertThat(titles()).containsExactly("A", "C", "B");

        assertThatThrownBy(() -> service.move(owner, b.getId(), b.getId())).isInstanceOf(ApiException.class);
    }

    @Test
    void manyMovesInTheSameGapRenumberInsteadOfGrowingForever() {
        TimelineEvent a = service.create(owner, universeId, "A", null);
        service.create(owner, universeId, "B", null);
        TimelineEvent c = service.create(owner, universeId, "C", null);
        TimelineEvent d = service.create(owner, universeId, "D", null);

        // Alterna C e D logo depois de A: o intervalo entre A e B vai sendo dividido ao meio
        for (int i = 0; i < 80; i++) {
            service.move(owner, (i % 2 == 0 ? c : d).getId(), a.getId());
        }

        assertThat(stored).allSatisfy(e -> assertThat(e.getSortKey().scale()).isLessThanOrEqualTo(TimelineService.MAX_SCALE + 3));
        assertThat(titles()).containsExactly("A", "D", "C", "B");
    }

    @Test
    void placeAndCharactersMustBeOfTheSameUniverse() {
        TimelineEvent event = service.create(owner, universeId, "Encontro", null);
        UUID strangerPlace = UUID.randomUUID();
        when(entities.findByIdAndKindAndDeletedAtIsNull(strangerPlace, UniverseEntity.PLACE)).thenReturn(Optional.of(
                UniverseEntity.builder().id(strangerPlace).universeId(UUID.randomUUID()).kind("place").name("X").build()));

        assertThatThrownBy(() -> service.update(owner, event.getId(), 0,
                new TimelineService.EventData("Encontro", null, null, null, strangerPlace, List.of())))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("event_invalid"));
        assertThatThrownBy(() -> service.update(owner, event.getId(), 0,
                new TimelineService.EventData("Encontro", null, null, null, null, List.of(UUID.randomUUID()))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("event_invalid"));
    }

    @Test
    void keyAfterTheLastIsOneStepAhead() {
        TimelineEvent only = TimelineEvent.builder().id(UUID.randomUUID()).sortKey(BigDecimal.valueOf(3000)).build();

        assertThat(TimelineService.keyAfter(List.of(only), only.getId())).isEqualByComparingTo("4000");
        assertThat(TimelineService.keyAfter(List.of(only), null)).isEqualByComparingTo("2000");
        assertThat(TimelineService.keyAfter(List.of(), null)).isEqualByComparingTo("1000");
    }

    private List<String> titles() {
        return events.findAllByUniverseIdAndDeletedAtIsNullOrderBySortKeyAsc(universeId).stream()
                .map(TimelineEvent::getTitle).toList();
    }
}
