package com.booksmith.universe.web;

import com.booksmith.identity.api.CurrentUser;
import com.booksmith.universe.internal.TimelineEvent;
import com.booksmith.universe.internal.TimelineService;
import com.booksmith.universe.internal.TimelineService.EventData;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Linha do tempo básica (§4.2 do produto): eventos na ordem do autor, ligados a capítulo, lugar e personagens. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
class TimelineController {

    private final TimelineService timelineService;
    private final CurrentUser currentUser;

    @GetMapping("/universes/{universeId}/timeline")
    List<EventResponse> list(@PathVariable UUID universeId) {
        List<TimelineEvent> events = timelineService.list(currentUser.id(), universeId);
        Map<UUID, String> names = timelineService.entityNames(universeId);
        return events.stream().map(e -> response(e, names)).toList();
    }

    /** Cria logo depois de {@code afterId}; sem ele, no fim. */
    @PostMapping("/universes/{universeId}/timeline")
    @ResponseStatus(HttpStatus.CREATED)
    EventResponse create(@PathVariable UUID universeId, @RequestBody @Valid CreateEventRequest request) {
        TimelineEvent event = timelineService.create(currentUser.id(), universeId, request.title(), request.afterId());
        return response(event, Map.of());
    }

    @PutMapping("/timeline/{eventId}")
    EventResponse update(@PathVariable UUID eventId, @RequestBody @Valid UpdateEventRequest request) {
        var data = new EventData(request.title(), request.whenLabel(), request.description(), request.chapterId(),
                request.placeId(), request.characterIds());
        TimelineEvent event = timelineService.update(currentUser.id(), eventId, request.version(), data);
        return response(event, timelineService.entityNames(event.getUniverseId()));
    }

    /** Move para logo depois de {@code afterId}; {@code afterId} nulo leva para o começo. */
    @PostMapping("/timeline/{eventId}/move")
    EventResponse move(@PathVariable UUID eventId, @RequestBody MoveEventRequest request) {
        TimelineEvent event = timelineService.move(currentUser.id(), eventId, request.afterId());
        return response(event, timelineService.entityNames(event.getUniverseId()));
    }

    @DeleteMapping("/timeline/{eventId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID eventId) {
        timelineService.delete(currentUser.id(), eventId);
    }

    private static EventResponse response(TimelineEvent event, Map<UUID, String> names) {
        // Personagens ou lugar excluídos depois somem do evento, sem erro
        List<EntityRef> characters = Arrays.stream(event.getCharacterIds())
                .filter(names::containsKey)
                .map(id -> new EntityRef(id, names.get(id)))
                .toList();
        EntityRef place = event.getPlaceId() != null && names.containsKey(event.getPlaceId())
                ? new EntityRef(event.getPlaceId(), names.get(event.getPlaceId()))
                : null;
        return new EventResponse(event.getId(), event.getUniverseId(), event.getTitle(), event.getWhenLabel(),
                event.getDescription(), event.getChapterId(), place, characters, event.getVersion(),
                Objects.requireNonNullElse(event.getUpdatedAt(), event.getCreatedAt()));
    }

    record CreateEventRequest(@NotBlank(message = "Dê um título ao evento.") @Size(max = 200) String title,
                              UUID afterId) {
    }

    record UpdateEventRequest(
            @NotNull Long version,
            @NotBlank(message = "Dê um título ao evento.") @Size(max = 200) String title,
            @Size(max = 100) String whenLabel,
            @Size(max = 4000) String description,
            UUID chapterId,
            UUID placeId,
            List<UUID> characterIds) {
    }

    record MoveEventRequest(UUID afterId) {
    }

    record EntityRef(UUID id, String name) {
    }

    record EventResponse(UUID id, UUID universeId, String title, String whenLabel, String description,
                         UUID chapterId, EntityRef place, List<EntityRef> characters, long version,
                         Instant updatedAt) {
    }
}
