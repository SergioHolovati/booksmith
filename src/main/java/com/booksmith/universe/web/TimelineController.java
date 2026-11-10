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
import com.booksmith.universe.internal.WorldCalendar;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
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
    private final JsonMapper jsonMapper;

    @GetMapping("/universes/{universeId}/timeline")
    List<EventResponse> list(@PathVariable UUID universeId) {
        List<TimelineEvent> events = timelineService.list(currentUser.id(), universeId);
        return responses(universeId, events);
    }

    /** Põe os eventos datados em ordem cronológica; os sem data ficam onde estavam. */
    @PostMapping("/universes/{universeId}/timeline/sort-by-date")
    List<EventResponse> sortByDate(@PathVariable UUID universeId) {
        timelineService.sortByDate(currentUser.id(), universeId);
        return responses(universeId, timelineService.list(currentUser.id(), universeId));
    }

    @GetMapping("/universes/{universeId}/calendar")
    JsonNode calendar(@PathVariable UUID universeId) {
        return timelineService.calendar(currentUser.id(), universeId).toJson(jsonMapper);
    }

    @PutMapping("/universes/{universeId}/calendar")
    JsonNode setCalendar(@PathVariable UUID universeId, @RequestBody JsonNode calendar) {
        return timelineService.setCalendar(currentUser.id(), universeId, calendar).toJson(jsonMapper);
    }

    /** Cria logo depois de {@code afterId}; sem ele, no fim. */
    @PostMapping("/universes/{universeId}/timeline")
    @ResponseStatus(HttpStatus.CREATED)
    EventResponse create(@PathVariable UUID universeId, @RequestBody @Valid CreateEventRequest request) {
        TimelineEvent event = timelineService.create(currentUser.id(), universeId, request.title(), request.afterId());
        return responses(universeId, List.of(event)).getFirst();
    }

    @PutMapping("/timeline/{eventId}")
    EventResponse update(@PathVariable UUID eventId, @RequestBody @Valid UpdateEventRequest request) {
        var data = new EventData(request.title(), request.whenLabel(), request.description(), request.chapterId(),
                request.placeId(), request.characterIds(), request.kind(), request.date(), request.loreIds());
        TimelineEvent event = timelineService.update(currentUser.id(), eventId, request.version(), data);
        return single(event);
    }

    /** Move para logo depois de {@code afterId}; {@code afterId} nulo leva para o começo. */
    @PostMapping("/timeline/{eventId}/move")
    EventResponse move(@PathVariable UUID eventId, @RequestBody MoveEventRequest request) {
        TimelineEvent event = timelineService.move(currentUser.id(), eventId, request.afterId());
        return single(event);
    }

    @DeleteMapping("/timeline/{eventId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID eventId) {
        timelineService.delete(currentUser.id(), eventId);
    }

    /** Um evento, com as idades calculadas a partir de todos os nascimentos do universo. */
    private EventResponse single(TimelineEvent event) {
        List<TimelineEvent> all = timelineService.list(currentUser.id(), event.getUniverseId());
        return responses(event.getUniverseId(), all).stream().filter(r -> r.id().equals(event.getId())).findFirst()
                .orElseGet(() -> responses(event.getUniverseId(), List.of(event)).getFirst());
    }

    private List<EventResponse> responses(UUID universeId, List<TimelineEvent> events) {
        Map<UUID, String> names = timelineService.entityNames(universeId);
        WorldCalendar calendar = timelineService.calendar(universeId);
        var births = timelineService.births(timelineService.list(currentUser.id(), universeId));
        return events.stream().map(e -> response(e, names, calendar, births)).toList();
    }

    private EventResponse response(TimelineEvent event, Map<UUID, String> names, WorldCalendar calendar,
                                   Map<UUID, WorldCalendar.WorldDate> births) {
        WorldCalendar.WorldDate date = timelineService.dateOf(event);
        List<Age> ages = new java.util.ArrayList<>();
        if (date != null && !"birth".equals(event.getKind())) {
            for (UUID character : event.getCharacterIds()) {
                WorldCalendar.WorldDate birth = births.get(character);
                Integer age = birth == null ? null : calendar.age(birth, date);
                if (age != null && names.containsKey(character)) {
                    ages.add(new Age(character, names.get(character), age));
                }
            }
        }
        List<EntityRef> lore = Arrays.stream(event.getLoreIds())
                .filter(names::containsKey)
                .map(id -> new EntityRef(id, names.get(id)))
                .toList();
        return response(event, names, date == null ? null : WorldCalendar.dateJson(date, jsonMapper),
                date == null ? null : calendar.label(date), ages, lore);
    }

    private static EventResponse response(TimelineEvent event, Map<UUID, String> names, JsonNode date,
                                          String dateLabel, List<Age> ages, List<EntityRef> lore) {
        // Personagens ou lugar excluídos depois somem do evento, sem erro
        List<EntityRef> characters = Arrays.stream(event.getCharacterIds())
                .filter(names::containsKey)
                .map(id -> new EntityRef(id, names.get(id)))
                .toList();
        EntityRef place = event.getPlaceId() != null && names.containsKey(event.getPlaceId())
                ? new EntityRef(event.getPlaceId(), names.get(event.getPlaceId()))
                : null;
        return new EventResponse(event.getId(), event.getUniverseId(), event.getTitle(), event.getWhenLabel(),
                event.getDescription(), event.getChapterId(), place, characters, event.getKind(), date, dateLabel,
                ages, lore, event.getVersion(), Objects.requireNonNullElse(event.getUpdatedAt(), event.getCreatedAt()));
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
            List<UUID> characterIds,
            String kind,
            JsonNode date,
            List<UUID> loreIds) {
    }

    /** Idade do personagem no evento, a partir do nascimento dele na linha do tempo. */
    record Age(UUID id, String name, int years) {
    }

    record MoveEventRequest(UUID afterId) {
    }

    record EntityRef(UUID id, String name) {
    }

    record EventResponse(UUID id, UUID universeId, String title, String whenLabel, String description,
                         UUID chapterId, EntityRef place, List<EntityRef> characters, String kind, JsonNode date,
                         String dateLabel, List<Age> ages, List<EntityRef> lore, long version, Instant updatedAt) {
    }
}
