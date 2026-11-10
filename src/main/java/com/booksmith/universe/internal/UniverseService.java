package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.media.api.ImageCategory;
import com.booksmith.media.api.ImageStorage;
import com.booksmith.universe.api.Universes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Universos (§4.2 do produto): uma série inteira pode viver num universo só, e os livros dela reaproveitam os
 * mesmos personagens, lugares e linha do tempo. Tudo é do dono: de outra pessoa, responde 404.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UniverseService implements Universes {

    static final int MAX_NAME = 120;

    private final UniverseRepository universeRepository;
    private final UniverseEntityRepository entityRepository;
    private final TimelineEventRepository timelineRepository;
    private final RelationRepository relationRepository;
    private final AppearanceForm appearanceForm;
    private final UniverseForm universeForm;
    private final ImageStorage imageStorage;
    private final UniverseImageRepository imageRepository;
    private final JsonMapper jsonMapper;

    @Override
    @Transactional
    public UUID create(UUID ownerId, String name) {
        Universe universe = universeRepository.save(Universe.builder()
                .ownerId(ownerId)
                .name(name == null || name.isBlank() ? "Meu universo" : limit(name.strip()))
                .build());
        return universe.getId();
    }

    @Override
    @Transactional(readOnly = true)
    public void requireOwned(UUID ownerId, UUID universeId) {
        require(ownerId, universeId);
    }

    @Override
    @Transactional
    public MergeResult merge(UUID ownerId, UUID from, UUID into) {
        require(ownerId, from);
        require(ownerId, into);
        if (from.equals(into)) {
            return new MergeResult(List.of());
        }

        Set<String> existing = new java.util.HashSet<>();
        Map<String, String> incoming = new LinkedHashMap<>();
        for (String kind : List.of(UniverseEntity.CHARACTER, UniverseEntity.PLACE)) {
            entityRepository.findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(into, kind)
                    .forEach(e -> existing.add(kind + "/" + key(e.getName())));
            entityRepository.findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(from, kind)
                    .forEach(e -> incoming.put(kind + "/" + key(e.getName()), e.getName()));
        }
        Set<String> duplicates = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        incoming.forEach((k, name) -> {
            if (existing.contains(k)) {
                duplicates.add(name);
            }
        });

        // A linha do tempo do universo que chega vem depois da que já existia, na mesma ordem
        BigDecimal offset = BigDecimal.ZERO;
        BigDecimal lastInto = timelineRepository.maxSortKey(into);
        BigDecimal firstFrom = timelineRepository.minSortKey(from);
        if (lastInto != null && firstFrom != null) {
            offset = lastInto.subtract(firstFrom).add(BigDecimal.ONE);
        }
        int events = timelineRepository.moveAll(from, into, offset);
        int entities = entityRepository.moveAll(from, into);
        relationRepository.moveAll(from, into);
        imageRepository.moveAll(from, into);

        // O conteúdo do construtor também vem: campos que o destino não tem, magias e leis somadas, e o mapa se
        // o destino ainda não tiver um
        Universe target = require(ownerId, into);
        Universe source = require(ownerId, from);
        target.setDetails(jsonMapper.writeValueAsString(universeForm.merge(
                jsonMapper.readTree(target.getDetails()), jsonMapper.readTree(source.getDetails()), jsonMapper)));
        if (target.getMapUrl() == null) {
            target.setMapUrl(source.getMapUrl());
            target.setMapGenerator(source.getMapGenerator());
        } else {
            imageStorage.delete(source.getMapUrl());
        }
        universeRepository.delete(source);
        log.info("Universo {} juntado a {}: {} entidades, {} eventos, {} nomes repetidos", from, into, entities, events,
                duplicates.size());
        return new MergeResult(List.copyOf(duplicates));
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<String> imageUrl(UUID universeId, UUID imageId) {
        return imageRepository.findByIdAndDeletedAtIsNull(imageId)
                .filter(image -> image.getUniverseId().equals(universeId))
                .map(UniverseImage::getUrl);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Set<String> vocabulary(UUID universeId) {
        java.util.Set<String> words = new java.util.LinkedHashSet<>();
        universeRepository.findById(universeId).ifPresent(u -> words.add(u.getName()));
        for (String kind : List.of(UniverseEntity.CHARACTER, UniverseEntity.PLACE, UniverseEntity.LORE)) {
            for (UniverseEntity entity : entityRepository.findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(universeId, kind)) {
                words.add(entity.getName());
                words.addAll(java.util.Arrays.asList(entity.getAliases()));
            }
        }
        words.removeIf(w -> w == null || w.isBlank());
        return words;
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnownEntity> entities(UUID universeId) {
        List<KnownEntity> result = new ArrayList<>();
        for (String kind : List.of(UniverseEntity.CHARACTER, UniverseEntity.PLACE)) {
            for (UniverseEntity entity : entityRepository.findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(universeId, kind)) {
                Map<String, Trait> appearance = new LinkedHashMap<>();
                jsonMapper.readTree(entity.getAppearance()).properties().forEach(e -> {
                    if (e.getValue().isString()) {
                        appearance.put(e.getKey(), new Trait(e.getValue().stringValue(),
                                appearanceForm.optionLabel(e.getKey(), e.getValue().stringValue())));
                    }
                });
                result.add(new KnownEntity(entity.getId(), kind, entity.getName(),
                        List.of(entity.getAliases()), appearance));
            }
        }
        return result;
    }

    /** Universos do usuário, com quantos personagens, lugares e eventos cada um tem. */
    @Transactional(readOnly = true)
    public List<UniverseWithCounts> list(UUID ownerId) {
        List<Universe> universes = universeRepository.findAllByOwnerIdOrderByNameAsc(ownerId);
        List<UUID> ids = universes.stream().map(Universe::getId).toList();
        Map<UUID, Map<String, Long>> entityCounts = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] row : entityRepository.countByUniverse(ids)) {
                entityCounts.computeIfAbsent((UUID) row[0], k -> new HashMap<>()).put((String) row[1], (Long) row[2]);
            }
        }
        Map<UUID, Long> eventCounts = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] row : timelineRepository.countByUniverse(ids)) {
                eventCounts.put((UUID) row[0], (Long) row[1]);
            }
        }
        return universes.stream().map(u -> {
            Map<String, Long> counts = entityCounts.getOrDefault(u.getId(), Map.of());
            return new UniverseWithCounts(u, counts.getOrDefault(UniverseEntity.CHARACTER, 0L),
                    counts.getOrDefault(UniverseEntity.PLACE, 0L), eventCounts.getOrDefault(u.getId(), 0L));
        }).toList();
    }

    @Transactional(readOnly = true)
    public UniverseWithCounts get(UUID ownerId, UUID universeId) {
        require(ownerId, universeId);
        return list(ownerId).stream().filter(u -> u.universe().getId().equals(universeId)).findFirst().orElseThrow();
    }

    /**
     * Salva o nome e o conteúdo do construtor. {@code expectedVersion} é a versão que o app abriu: se outro
     * aparelho salvou antes, responde 409 com a versão atual, e o app avisa em vez de sobrescrever.
     */
    @Transactional
    public Universe saveDetails(UUID ownerId, UUID universeId, long expectedVersion, String name, JsonNode details) {
        Universe universe = require(ownerId, universeId);
        if (universe.getVersion() != expectedVersion) {
            throw ApiException.conflict("universe_version_conflict", "Este universo foi alterado em outro aparelho.",
                    Map.of("currentVersion", universe.getVersion()));
        }
        universe.setName(EntityNames.require(name, "universe_invalid", "Dê um nome ao universo."));
        universe.setDetails(jsonMapper.writeValueAsString(universeForm.normalize(details, jsonMapper)));
        return universeRepository.saveAndFlush(universe);
    }

    /**
     * Troca a imagem do mapa (data URL); a anterior é apagada do armazenamento. {@code generator}: semente e
     * ajustes, quando o mapa foi gerado no app; nulo para imagem enviada.
     */
    @Transactional
    public Universe setMap(UUID ownerId, UUID universeId, String dataUrl, JsonNode generator) {
        Universe universe = require(ownerId, universeId);
        String previous = universe.getMapUrl();
        universe.setMapGenerator(generator == null || generator.isNull() ? null
                : jsonMapper.writeValueAsString(MapGenerator.normalize(generator, jsonMapper)));
        universe.setMapUrl(imageStorage.store(ImageCategory.UNIVERSE_MAP, ownerId, dataUrl).url());
        imageStorage.delete(previous);
        return universeRepository.saveAndFlush(universe);
    }

    @Transactional
    public Universe removeMap(UUID ownerId, UUID universeId) {
        Universe universe = require(ownerId, universeId);
        imageStorage.delete(universe.getMapUrl());
        universe.setMapUrl(null);
        universe.setMapGenerator(null);
        return universeRepository.saveAndFlush(universe);
    }

    public JsonNode details(Universe universe) {
        return jsonMapper.readTree(universe.getDetails());
    }

    public JsonNode mapGenerator(Universe universe) {
        return universe.getMapGenerator() == null ? null : jsonMapper.readTree(universe.getMapGenerator());
    }

    @Transactional
    public Universe rename(UUID ownerId, UUID universeId, String name) {
        Universe universe = require(ownerId, universeId);
        universe.setName(EntityNames.require(name, "universe_invalid", "Dê um nome ao universo."));
        return universe;
    }

    private Universe require(UUID ownerId, UUID universeId) {
        return universeRepository.findByIdAndOwnerId(universeId, ownerId)
                .orElseThrow(() -> ApiException.notFound("universe_not_found", "Universo não encontrado."));
    }

    private static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    private static String limit(String name) {
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME).strip() : name;
    }

    public record UniverseWithCounts(Universe universe, long characters, long places, long events) {
    }
}
