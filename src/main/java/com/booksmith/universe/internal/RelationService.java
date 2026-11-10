package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Relações entre personagens (o "mapa de relações", §4.2 do produto). Tipos com direção leem de {@code from}
 * para {@code to} ("Ana é mãe de Lia", "Kael serve a Ysolde"); os outros valem para os dois lados ("irmãos",
 * "rivais"). Tudo é do dono do universo: de outra pessoa, responde 404.
 */
@Service
@RequiredArgsConstructor
public class RelationService {

    /** Leem de "from" para "to". */
    static final Set<String> DIRECTED = Set.of("parent", "mentor", "serves");
    static final Set<String> KINDS = Set.of("parent", "sibling", "spouse", "romance", "friend", "mentor", "rival",
            "enemy", "ally", "serves", "other");
    static final int MAX_RELATIONS = 2_000;
    static final int MAX_DESCRIPTION = 2_000;

    private final UniverseRepository universeRepository;
    private final UniverseEntityRepository entityRepository;
    private final TimelineEventRepository eventRepository;
    private final RelationRepository relationRepository;
    private final Clock clock;

    /** Relações do universo cujos dois personagens ainda existem. */
    @Transactional(readOnly = true)
    public List<Relation> list(UUID ownerId, UUID universeId) {
        requireUniverse(ownerId, universeId);
        Set<UUID> alive = characterIds(universeId);
        return relationRepository.findAllByUniverseIdAndDeletedAtIsNull(universeId).stream()
                .filter(r -> alive.contains(r.getFromId()) && alive.contains(r.getToId()))
                .toList();
    }

    @Transactional
    public Relation create(UUID ownerId, UUID universeId, RelationData data) {
        requireUniverse(ownerId, universeId);
        if (relationRepository.findAllByUniverseIdAndDeletedAtIsNull(universeId).size() >= MAX_RELATIONS) {
            throw ApiException.unprocessable("too_many_relations",
                    "Este universo chegou a %d relações.".formatted(MAX_RELATIONS));
        }
        Relation relation = Relation.builder().universeId(universeId).build();
        apply(relation, data, null);
        return relationRepository.save(relation);
    }

    @Transactional
    public Relation update(UUID ownerId, UUID relationId, long expectedVersion, RelationData data) {
        Relation relation = require(ownerId, relationId);
        if (relation.getVersion() != expectedVersion) {
            throw ApiException.conflict("relation_version_conflict", "Esta relação foi alterada em outro aparelho.",
                    Map.of("currentVersion", relation.getVersion()));
        }
        apply(relation, data, relation.getId());
        return relationRepository.saveAndFlush(relation);
    }

    @Transactional
    public void delete(UUID ownerId, UUID relationId) {
        require(ownerId, relationId).setDeletedAt(Instant.now(clock));
    }

    public boolean directed(String kind) {
        return DIRECTED.contains(kind);
    }

    // ===== Validação =====

    private void apply(Relation relation, RelationData data, UUID self) {
        UUID universeId = relation.getUniverseId();
        if (data.kind() == null || !KINDS.contains(data.kind())) {
            throw invalid("escolha o tipo da relação");
        }
        if (data.fromId() == null || data.toId() == null || data.fromId().equals(data.toId())) {
            throw invalid("escolha dois personagens diferentes");
        }
        Set<UUID> characters = characterIds(universeId);
        if (!characters.contains(data.fromId()) || !characters.contains(data.toId())) {
            throw invalid("os dois personagens precisam ser deste universo");
        }
        boolean repeated = relationRepository.findAllByUniverseIdAndDeletedAtIsNull(universeId).stream()
                .filter(r -> !r.getId().equals(self) && r.getKind().equals(data.kind()))
                .anyMatch(r -> (r.getFromId().equals(data.fromId()) && r.getToId().equals(data.toId()))
                        || (!directed(data.kind()) && r.getFromId().equals(data.toId())
                        && r.getToId().equals(data.fromId())));
        if (repeated) {
            throw invalid("essa relação já existe");
        }
        String description = data.description() == null ? null : data.description().strip();
        if (description != null && description.length() > MAX_DESCRIPTION) {
            throw invalid("a descrição passou de %d caracteres".formatted(MAX_DESCRIPTION));
        }
        relation.setFromId(data.fromId());
        relation.setToId(data.toId());
        relation.setKind(data.kind());
        relation.setDescription(description == null || description.isEmpty() ? null : description);
        relation.setSinceEventId(checkEvent(universeId, data.sinceEventId()));
        relation.setUntilEventId(checkEvent(universeId, data.untilEventId()));
    }

    private UUID checkEvent(UUID universeId, UUID eventId) {
        if (eventId == null) {
            return null;
        }
        boolean ok = eventRepository.findByIdAndDeletedAtIsNull(eventId)
                .filter(e -> e.getUniverseId().equals(universeId)).isPresent();
        if (!ok) {
            throw invalid("o evento precisa ser da linha do tempo deste universo");
        }
        return eventId;
    }

    private Set<UUID> characterIds(UUID universeId) {
        Set<UUID> ids = new java.util.HashSet<>();
        entityRepository.findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(universeId, UniverseEntity.CHARACTER)
                .forEach(c -> ids.add(c.getId()));
        return ids;
    }

    private void requireUniverse(UUID ownerId, UUID universeId) {
        universeRepository.findByIdAndOwnerId(universeId, ownerId)
                .orElseThrow(() -> ApiException.notFound("universe_not_found", "Universo não encontrado."));
    }

    private Relation require(UUID ownerId, UUID relationId) {
        Relation relation = relationRepository.findByIdAndDeletedAtIsNull(relationId)
                .orElseThrow(RelationService::notFound);
        if (universeRepository.findByIdAndOwnerId(relation.getUniverseId(), ownerId).isEmpty()) {
            throw notFound();
        }
        return relation;
    }

    private static ApiException notFound() {
        return ApiException.notFound("relation_not_found", "Relação não encontrada.");
    }

    private static ApiException invalid(String reason) {
        return ApiException.unprocessable("relation_invalid", "Relação inválida: " + reason + ".");
    }

    public record RelationData(UUID fromId, UUID toId, String kind, String description, UUID sinceEventId,
                               UUID untilEventId) {
    }
}
