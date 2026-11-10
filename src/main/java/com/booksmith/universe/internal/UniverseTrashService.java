package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.media.api.ImageStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lixeira do universo (§20.6): personagens, lugares, fichas do mundo, eventos, relações e imagens excluídos nos últimos
 * 30 dias voltam com um toque, como livros e capítulos. Depois do prazo, o job diário apaga de vez.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UniverseTrashService {

    public static final Duration RETENTION = Duration.ofDays(30);

    public enum ItemType { CHARACTER, PLACE, LORE, EVENT, RELATION, IMAGE }

    private final UniverseRepository universeRepository;
    private final UniverseEntityRepository entityRepository;
    private final TimelineEventRepository eventRepository;
    private final RelationRepository relationRepository;
    private final UniverseImageRepository imageRepository;
    private final ImageStorage imageStorage;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<TrashedItem> list(UUID ownerId) {
        Instant cutoff = cutoff();
        Map<UUID, String> universes = new HashMap<>();
        universeRepository.findAllByOwnerIdOrderByNameAsc(ownerId).forEach(u -> universes.put(u.getId(), u.getName()));
        List<TrashedItem> items = new ArrayList<>();
        for (UniverseEntity entity : entityRepository.findTrashed(ownerId, cutoff)) {
            ItemType type = switch (entity.getKind()) {
                case UniverseEntity.CHARACTER -> ItemType.CHARACTER;
                case UniverseEntity.PLACE -> ItemType.PLACE;
                default -> ItemType.LORE;
            };
            items.add(item(type, entity.getId(), entity.getName(), entity.getUniverseId(), universes, entity.getDeletedAt()));
        }
        for (TimelineEvent event : eventRepository.findTrashed(ownerId, cutoff)) {
            items.add(item(ItemType.EVENT, event.getId(), event.getTitle(), event.getUniverseId(), universes,
                    event.getDeletedAt()));
        }
        for (UniverseImage image : imageRepository.findTrashed(ownerId, cutoff)) {
            items.add(item(ItemType.IMAGE, image.getId(), image.getCaption() == null ? "Imagem" : image.getCaption(),
                    image.getUniverseId(), universes, image.getDeletedAt()));
        }
        List<Relation> relations = relationRepository.findTrashed(ownerId, cutoff);
        Map<UUID, String> names = new HashMap<>();
        entityRepository.findAllById(relations.stream().flatMap(r -> java.util.stream.Stream.of(r.getFromId(), r.getToId()))
                .distinct().toList()).forEach(e -> names.put(e.getId(), e.getName()));
        for (Relation relation : relations) {
            String title = "%s e %s".formatted(names.getOrDefault(relation.getFromId(), "?"),
                    names.getOrDefault(relation.getToId(), "?"));
            items.add(item(ItemType.RELATION, relation.getId(), title, relation.getUniverseId(), universes,
                    relation.getDeletedAt()));
        }
        items.sort(Comparator.comparing(TrashedItem::deletedAt).reversed());
        return items;
    }

    /**
     * Restaura o item. Um lugar volta solto se o "dentro de" dele não existe mais; uma relação só volta se os dois
     * personagens existem (senão, restaure o personagem primeiro).
     */
    @Transactional
    public TrashedItem restore(UUID ownerId, ItemType type, UUID id) {
        Instant cutoff = cutoff();
        return switch (type) {
            case CHARACTER, PLACE, LORE -> {
                UniverseEntity entity = entityRepository.findById(id)
                        .filter(e -> e.getDeletedAt() != null && e.getDeletedAt().isAfter(cutoff))
                        .filter(e -> owns(ownerId, e.getUniverseId()))
                        .orElseThrow(UniverseTrashService::notFound);
                entity.setDeletedAt(null);
                if (UniverseEntity.PLACE.equals(entity.getKind())) {
                    detachFromMissingParent(entity);
                }
                log.info("Restaurado da lixeira: {} {}", type, id);
                yield new TrashedItem(type, id, entity.getName(), entity.getUniverseId(), null, null, null);
            }
            case IMAGE -> {
                UniverseImage image = imageRepository.findById(id)
                        .filter(i -> i.getDeletedAt() != null && i.getDeletedAt().isAfter(cutoff))
                        .filter(i -> owns(ownerId, i.getUniverseId()))
                        .orElseThrow(UniverseTrashService::notFound);
                image.setDeletedAt(null);
                yield new TrashedItem(type, id, image.getCaption(), image.getUniverseId(), null, null, null);
            }
            case EVENT -> {
                TimelineEvent event = eventRepository.findById(id)
                        .filter(e -> e.getDeletedAt() != null && e.getDeletedAt().isAfter(cutoff))
                        .filter(e -> owns(ownerId, e.getUniverseId()))
                        .orElseThrow(UniverseTrashService::notFound);
                event.setDeletedAt(null);
                yield new TrashedItem(type, id, event.getTitle(), event.getUniverseId(), null, null, null);
            }
            case RELATION -> {
                Relation relation = relationRepository.findById(id)
                        .filter(r -> r.getDeletedAt() != null && r.getDeletedAt().isAfter(cutoff))
                        .filter(r -> owns(ownerId, r.getUniverseId()))
                        .orElseThrow(UniverseTrashService::notFound);
                for (UUID character : List.of(relation.getFromId(), relation.getToId())) {
                    UniverseEntity c = entityRepository.findById(character).orElse(null);
                    if (c == null || c.getDeletedAt() != null) {
                        throw ApiException.unprocessable("relation_needs_character",
                                "Restaure antes %s, que também está na lixeira.".formatted(c == null ? "o personagem" : c.getName()));
                    }
                }
                relation.setDeletedAt(null);
                yield new TrashedItem(type, id, null, relation.getUniverseId(), null, null, null);
            }
        };
    }

    /**
     * Apaga de vez o que passou de 30 dias na lixeira, todo dia às 3h40 (depois dos livros). As relações dos
     * personagens apagados vão junto; "desde" e "até" que apontam para eventos apagados ficam vazios; as
     * imagens saem também do armazenamento. As outras
     * referências (personagens de um evento, lugares de uma ficha, lugar-pai) são lógicas e já ignoram o que não
     * existe.
     */
    @Scheduled(cron = "${booksmith.trash.universe-purge-cron:0 40 3 * * *}")
    @Transactional
    public void purgeExpired() {
        Instant cutoff = cutoff();
        int relations = relationRepository.purgeDeletedBefore(cutoff);
        relationRepository.clearSinceBefore(cutoff);
        relationRepository.clearUntilBefore(cutoff);
        int entities = entityRepository.purgeDeletedBefore(cutoff);
        int events = eventRepository.purgeDeletedBefore(cutoff);
        List<UniverseImage> images = imageRepository.findAllByDeletedAtBefore(cutoff);
        images.forEach(image -> imageStorage.delete(image.getUrl())); // falhas são só registradas
        imageRepository.deleteAll(images);
        if (relations + entities + events + images.size() > 0) {
            log.info("Lixeira do universo: {} fichas, {} eventos, {} relações e {} imagens apagados de vez "
                    + "(excluídos antes de {})", entities, events, relations, images.size(), cutoff);
        }
    }

    private void detachFromMissingParent(UniverseEntity place) {
        ObjectNode sheet = (ObjectNode) jsonMapper.readTree(place.getAttributes());
        JsonNode parent = sheet.get("parentId");
        if (parent == null) {
            return;
        }
        boolean alive = entityRepository.findByIdAndKindAndDeletedAtIsNull(UUID.fromString(parent.stringValue()),
                UniverseEntity.PLACE).isPresent();
        if (!alive) {
            sheet.remove("parentId");
            place.setAttributes(jsonMapper.writeValueAsString(sheet));
        }
    }

    private boolean owns(UUID ownerId, UUID universeId) {
        return universeRepository.findByIdAndOwnerId(universeId, ownerId).isPresent();
    }

    private TrashedItem item(ItemType type, UUID id, String title, UUID universeId, Map<UUID, String> universes,
                             Instant deletedAt) {
        return new TrashedItem(type, id, title, universeId, universes.get(universeId), deletedAt,
                deletedAt.plus(RETENTION));
    }

    private Instant cutoff() {
        return Instant.now(clock).minus(RETENTION);
    }

    private static ApiException notFound() {
        return ApiException.notFound("trash_item_not_found", "Este item não está mais na lixeira.");
    }

    public record TrashedItem(ItemType type, UUID id, String title, UUID universeId, String universeName,
                              Instant deletedAt, Instant expiresAt) {
    }
}
