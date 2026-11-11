package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lugares do universo. Um lugar pode estar "dentro de" outro (bairro na cidade, sala no castelo): a
 * hierarquia não pode ter ciclos, e excluir um lugar sobe os de dentro um nível.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceService {

    static final int MAX_PLACES = 500;
    /** Profundidade máxima de "dentro de" (planeta → país → cidade → bairro → prédio → sala…). */
    static final int MAX_DEPTH = 12;
    static final int MAX_MAP_IMPORT = 300;

    private final UniverseRepository universeRepository;
    private final UniverseEntityRepository entityRepository;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final CustomFieldService customFieldService;

    @Transactional(readOnly = true)
    public List<UniverseEntity> list(UUID ownerId, UUID universeId) {
        requireUniverse(ownerId, universeId);
        return places(universeId);
    }

    @Transactional
    public UniverseEntity create(UUID ownerId, UUID universeId, String name) {
        requireUniverse(ownerId, universeId);
        if (places(universeId).size() >= MAX_PLACES) {
            throw ApiException.unprocessable("too_many_places",
                    "Este universo chegou a %d lugares.".formatted(MAX_PLACES));
        }
        return entityRepository.save(UniverseEntity.builder()
                .universeId(universeId)
                .kind(UniverseEntity.PLACE)
                .name(EntityNames.require(name, "place_invalid", "Dê um nome ao lugar."))
                .build());
    }

    @Transactional(readOnly = true)
    public UniverseEntity get(UUID ownerId, UUID placeId) {
        return require(ownerId, placeId);
    }

    @Transactional
    public UniverseEntity update(UUID ownerId, UUID placeId, long expectedVersion, PlaceData data) {
        UniverseEntity place = require(ownerId, placeId);
        if (place.getVersion() != expectedVersion) {
            throw ApiException.conflict("place_version_conflict", "Este lugar foi alterado em outro aparelho.",
                    Map.of("currentVersion", place.getVersion()));
        }
        ObjectNode sheet = PlaceSheet.normalize(data.sheet(), jsonMapper);
        if (sheet.has("parentId")) {
            checkParent(place, UUID.fromString(sheet.get("parentId").stringValue()));
        }
        place.setName(EntityNames.require(data.name(), "place_invalid", "Dê um nome ao lugar."));
        place.setAliases(CharacterSheet.aliases(data.aliases()));
        place.setAttributes(jsonMapper.writeValueAsString(sheet));
        customFieldService.apply(place, UniverseEntity.PLACE, data.custom());
        return entityRepository.saveAndFlush(place);
    }

    /**
     * Cria de uma vez os lugares do mapa gerado (países, cidades, florestas…), com "dentro de" e posição no mapa.
     * {@code parentKey} aponta para um item anterior da mesma lista. Nome que já existe no universo não é criado
     * de novo (dá para repetir sem duplicar): o lugar existente serve de "dentro de" para os itens abaixo dele.
     */
    @Transactional
    public MapImport importFromMap(UUID ownerId, UUID universeId, List<MapPlace> items) {
        requireUniverse(ownerId, universeId);
        if (items.size() > MAX_MAP_IMPORT) {
            throw PlaceSheet.invalid("no máximo %d lugares por vez".formatted(MAX_MAP_IMPORT));
        }
        Map<String, UniverseEntity> existing = new HashMap<>();
        for (UniverseEntity place : places(universeId)) {
            existing.putIfAbsent(key(place.getName()), place);
        }
        Map<String, UUID> byKey = new HashMap<>();
        List<String> skipped = new ArrayList<>();
        int created = 0;
        for (MapPlace item : items) {
            String name = EntityNames.require(item.name(), "place_invalid", "Dê um nome ao lugar.");
            UniverseEntity same = existing.get(key(name));
            if (same != null) {
                skipped.add(name);
                byKey.put(item.key(), same.getId());
                continue;
            }
            if (existing.size() >= MAX_PLACES) {
                throw ApiException.unprocessable("too_many_places",
                        "Este universo chegou a %d lugares.".formatted(MAX_PLACES));
            }
            ObjectNode sheet = jsonMapper.createObjectNode();
            putIfPresent(sheet, "type", item.type());
            putIfPresent(sheet, "description", item.description());
            putIfPresent(sheet, "atmosphere", item.atmosphere());
            if (item.parentKey() != null) {
                UUID parent = byKey.get(item.parentKey());
                if (parent == null) {
                    throw PlaceSheet.invalid("\"%s\" está dentro de um lugar que não veio antes na lista".formatted(name));
                }
                sheet.put("parentId", parent.toString());
            }
            UniverseEntity place = entityRepository.save(UniverseEntity.builder()
                    .universeId(universeId)
                    .kind(UniverseEntity.PLACE)
                    .name(name)
                    .attributes(jsonMapper.writeValueAsString(PlaceSheet.normalize(sheet, jsonMapper)))
                    .mapX(fraction(item.x(), name))
                    .mapY(fraction(item.y(), name))
                    .build());
            existing.put(key(name), place);
            byKey.put(item.key(), place.getId());
            created++;
        }
        log.info("Lugares do mapa no universo {}: {} criados, {} já existiam", universeId, created, skipped.size());
        return new MapImport(created, skipped);
    }

    private static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    private static void putIfPresent(ObjectNode sheet, String field, String value) {
        if (value != null && !value.isBlank()) {
            sheet.put(field, value);
        }
    }

    private static Float fraction(Double value, String name) {
        if (value == null) {
            return null;
        }
        if (value.isNaN() || value < 0 || value > 1) {
            throw PlaceSheet.invalid("posição de \"%s\" fora do mapa".formatted(name));
        }
        return value.floatValue();
    }

    /** Exclui o lugar; o que estava dentro dele passa para o lugar de cima (ou fica solto). */
    @Transactional
    public void delete(UUID ownerId, UUID placeId) {
        UniverseEntity place = require(ownerId, placeId);
        Optional<String> grandparent = parentOf(place).map(UUID::toString);
        for (UniverseEntity child : places(place.getUniverseId())) {
            if (parentOf(child).filter(placeId::equals).isPresent()) {
                ObjectNode sheet = (ObjectNode) jsonMapper.readTree(child.getAttributes());
                grandparent.ifPresentOrElse(id -> sheet.put("parentId", id), () -> sheet.remove("parentId"));
                child.setAttributes(jsonMapper.writeValueAsString(sheet));
            }
        }
        place.setDeletedAt(Instant.now(clock));
        log.info("Lugar {} excluído", placeId);
    }

    /** Nome de cada lugar do universo, para mostrar "dentro de X" sem uma consulta por lugar. */
    public Map<UUID, String> names(UUID universeId) {
        return places(universeId).stream().collect(Collectors.toMap(UniverseEntity::getId, UniverseEntity::getName));
    }

    public Optional<UUID> parentOf(UniverseEntity place) {
        JsonNode parent = jsonMapper.readTree(place.getAttributes()).get("parentId");
        return parent == null ? Optional.empty() : Optional.of(UUID.fromString(parent.stringValue()));
    }

    // ===== Internos =====

    /** O "dentro de" precisa ser outro lugar do mesmo universo, sem formar ciclo nem passar da profundidade. */
    private void checkParent(UniverseEntity place, UUID parentId) {
        Map<UUID, UniverseEntity> byId = places(place.getUniverseId()).stream()
                .collect(Collectors.toMap(UniverseEntity::getId, Function.identity()));
        if (!byId.containsKey(parentId)) {
            throw PlaceSheet.invalid("\"dentro de\" precisa ser um lugar deste universo");
        }
        Set<UUID> seen = new HashSet<>();
        UUID current = parentId;
        int depth = 1;
        while (current != null) {
            if (current.equals(place.getId()) || !seen.add(current)) {
                throw PlaceSheet.invalid("um lugar não pode ficar dentro dele mesmo");
            }
            if (++depth > MAX_DEPTH) {
                throw PlaceSheet.invalid("lugares aninhados demais (máximo %d níveis)".formatted(MAX_DEPTH));
            }
            UniverseEntity parent = byId.get(current);
            current = parent == null ? null : parentOf(parent).orElse(null);
        }
    }

    private List<UniverseEntity> places(UUID universeId) {
        return entityRepository.findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(universeId, UniverseEntity.PLACE);
    }

    private void requireUniverse(UUID ownerId, UUID universeId) {
        universeRepository.findByIdAndOwnerId(universeId, ownerId)
                .orElseThrow(() -> ApiException.notFound("universe_not_found", "Universo não encontrado."));
    }

    private UniverseEntity require(UUID ownerId, UUID placeId) {
        UniverseEntity place = entityRepository.findByIdAndKindAndDeletedAtIsNull(placeId, UniverseEntity.PLACE)
                .orElseThrow(PlaceService::notFound);
        if (universeRepository.findByIdAndOwnerId(place.getUniverseId(), ownerId).isEmpty()) {
            throw notFound();
        }
        return place;
    }

    private static ApiException notFound() {
        return ApiException.notFound("place_not_found", "Lugar não encontrado.");
    }

    /** {@code custom}: valores dos campos próprios; nulo mantém os que estavam. */
    public record PlaceData(String name, List<String> aliases, JsonNode sheet, JsonNode custom) {
        public PlaceData(String name, List<String> aliases, JsonNode sheet) {
            this(name, aliases, sheet, null);
        }
    }

    /**
     * Um lugar do mapa gerado. {@code key}: identificador dentro do lote, para o "dentro de"; {@code x} e
     * {@code y}: posição em frações da imagem.
     */
    public record MapPlace(String key, String name, String type, String parentKey, Double x, Double y,
                           String description, String atmosphere) {
    }

    /** @param skipped nomes que já existiam no universo e não foram criados de novo */
    public record MapImport(int created, List<String> skipped) {
    }
}
