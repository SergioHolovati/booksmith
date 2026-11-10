package com.booksmith.universe.web;

import com.booksmith.identity.api.CurrentUser;
import com.booksmith.universe.internal.Relation;
import com.booksmith.universe.internal.RelationService;
import com.booksmith.universe.internal.RelationService.RelationData;
import com.booksmith.universe.internal.TimelineService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Relações entre personagens (§4.2 do produto). A ficha mostra as de um personagem; o mapa, todas. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
class RelationController {

    private final RelationService relationService;
    private final TimelineService timelineService;
    private final CurrentUser currentUser;

    @GetMapping("/universes/{universeId}/relations")
    List<RelationResponse> list(@PathVariable UUID universeId) {
        List<Relation> relations = relationService.list(currentUser.id(), universeId);
        Map<UUID, String> names = timelineService.entityNames(universeId);
        Map<UUID, String> events = eventTitles(universeId);
        return relations.stream().map(r -> response(r, names, events)).toList();
    }

    @PostMapping("/universes/{universeId}/relations")
    @ResponseStatus(HttpStatus.CREATED)
    RelationResponse create(@PathVariable UUID universeId, @RequestBody @Valid RelationRequest request) {
        Relation relation = relationService.create(currentUser.id(), universeId, request.data());
        return response(relation, timelineService.entityNames(universeId), eventTitles(universeId));
    }

    @PutMapping("/relations/{relationId}")
    RelationResponse update(@PathVariable UUID relationId, @RequestBody @Valid UpdateRelationRequest request) {
        Relation relation = relationService.update(currentUser.id(), relationId, request.version(), request.data());
        return response(relation, timelineService.entityNames(relation.getUniverseId()),
                eventTitles(relation.getUniverseId()));
    }

    @DeleteMapping("/relations/{relationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID relationId) {
        relationService.delete(currentUser.id(), relationId);
    }

    private Map<UUID, String> eventTitles(UUID universeId) {
        Map<UUID, String> titles = new java.util.HashMap<>();
        timelineService.list(currentUser.id(), universeId).forEach(e -> titles.put(e.getId(), e.getTitle()));
        return titles;
    }

    private RelationResponse response(Relation r, Map<UUID, String> names, Map<UUID, String> events) {
        return new RelationResponse(r.getId(), r.getUniverseId(), new Ref(r.getFromId(), names.get(r.getFromId())),
                new Ref(r.getToId(), names.get(r.getToId())), r.getKind(), relationService.directed(r.getKind()),
                r.getDescription(), ref(r.getSinceEventId(), events), ref(r.getUntilEventId(), events), r.getVersion(),
                r.getUpdatedAt());
    }

    private static Ref ref(UUID id, Map<UUID, String> names) {
        return id == null || !names.containsKey(id) ? null : new Ref(id, names.get(id));
    }

    record RelationRequest(@NotNull UUID fromId, @NotNull UUID toId, @NotNull String kind, String description,
                           UUID sinceEventId, UUID untilEventId) {

        RelationData data() {
            return new RelationData(fromId, toId, kind, description, sinceEventId, untilEventId);
        }
    }

    record UpdateRelationRequest(@NotNull Long version, @NotNull UUID fromId, @NotNull UUID toId, @NotNull String kind,
                                 String description, UUID sinceEventId, UUID untilEventId) {

        RelationData data() {
            return new RelationData(fromId, toId, kind, description, sinceEventId, untilEventId);
        }
    }

    record Ref(UUID id, String name) {
    }

    /** {@code directed}: o tipo lê de {@code from} para {@code to} ("é mãe de"); senão vale para os dois. */
    record RelationResponse(UUID id, UUID universeId, Ref from, Ref to, String kind, boolean directed,
                            String description, Ref since, Ref until, long version, Instant updatedAt) {
    }
}
