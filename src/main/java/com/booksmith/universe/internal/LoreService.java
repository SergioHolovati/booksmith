package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Contexto do mundo: religiões, sistemas políticos, idiomas e organizações, cada um presente em lugares do
 * universo (uma religião em vários países, um governo num país). Moram em {@code universe.entities} com
 * {@code kind = lore}: {@code attributes} guarda {@code type} e {@code placeIds}; {@code appearance}, os valores
 * da ficha ({@link LoreForm}). Tudo é do dono do universo: de outra pessoa, responde 404.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoreService {

    static final int MAX_LORE = 500;
    static final int MAX_PLACES_PER_ITEM = 100;
    static final int MAX_BATCH = 200;

    private final UniverseRepository universeRepository;
    private final UniverseEntityRepository entityRepository;
    private final LoreForm loreForm;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final CustomFieldService customFieldService;

    @Transactional(readOnly = true)
    public List<UniverseEntity> list(UUID ownerId, UUID universeId, String type) {
        requireUniverse(ownerId, universeId);
        List<UniverseEntity> all = lore(universeId);
        return type == null ? all : all.stream().filter(e -> type.equals(typeOf(e))).toList();
    }

    @Transactional
    public UniverseEntity create(UUID ownerId, UUID universeId, String type, String name) {
        requireUniverse(ownerId, universeId);
        loreForm.requireType(type);
        if (lore(universeId).size() >= MAX_LORE) {
            throw ApiException.unprocessable("too_much_lore", "Este universo chegou a %d itens.".formatted(MAX_LORE));
        }
        return entityRepository.save(UniverseEntity.builder()
                .universeId(universeId)
                .kind(UniverseEntity.LORE)
                .name(EntityNames.require(name, "lore_invalid", "Dê um nome."))
                .attributes(attributes(type, List.of()))
                .build());
    }

    @Transactional(readOnly = true)
    public UniverseEntity get(UUID ownerId, UUID loreId) {
        return require(ownerId, loreId);
    }

    /**
     * Salva a ficha inteira e os lugares onde o item está presente (só lugares do mesmo universo).
     * {@code expectedVersion} desatualizada → 409, para não sobrescrever o que outro aparelho salvou.
     */
    @Transactional
    public UniverseEntity update(UUID ownerId, UUID loreId, long expectedVersion, LoreData data) {
        UniverseEntity item = require(ownerId, loreId);
        if (item.getVersion() != expectedVersion) {
            throw ApiException.conflict("lore_version_conflict", "Esta ficha foi alterada em outro aparelho.",
                    Map.of("currentVersion", item.getVersion()));
        }
        String type = typeOf(item);
        item.setName(EntityNames.require(data.name(), "lore_invalid", "Dê um nome."));
        item.setAliases(CharacterSheet.aliases(data.aliases()));
        item.setAppearance(jsonMapper.writeValueAsString(loreForm.normalize(type, data.values(), jsonMapper)));
        item.setAttributes(attributes(type, checkPlaces(item.getUniverseId(), data.placeIds())));
        customFieldService.apply(item, type, data.custom());
        return entityRepository.saveAndFlush(item);
    }

    @Transactional
    public void delete(UUID ownerId, UUID loreId) {
        require(ownerId, loreId).setDeletedAt(Instant.now(clock));
        log.info("Item do mundo {} excluído", loreId);
    }

    /** O que existe num lugar: religiões, governo, idiomas e organizações que o incluem. */
    @Transactional(readOnly = true)
    public List<UniverseEntity> atPlace(UUID ownerId, UUID universeId, UUID placeId) {
        requireUniverse(ownerId, universeId);
        return lore(universeId).stream().filter(e -> placeIds(e).contains(placeId)).toList();
    }

    /**
     * Cria vários de uma vez (o mundo gerado com o mapa). Os lugares vêm pelo nome e são procurados entre os
     * lugares do universo; nome de lugar que não existe é ignorado. Item com o mesmo tipo e nome de um que já
     * existe não é criado de novo.
     */
    @Transactional
    public LoreImport importBatch(UUID ownerId, UUID universeId, List<LoreDraft> drafts) {
        requireUniverse(ownerId, universeId);
        if (drafts.size() > MAX_BATCH) {
            throw LoreForm.invalid(null, "no máximo %d itens por vez".formatted(MAX_BATCH));
        }
        Map<String, UUID> placeByName = new HashMap<>();
        for (UniverseEntity place : entityRepository.findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(
                universeId, UniverseEntity.PLACE)) {
            placeByName.putIfAbsent(key(place.getName()), place.getId());
        }
        Set<String> existing = new java.util.HashSet<>();
        List<UniverseEntity> current = lore(universeId);
        current.forEach(e -> existing.add(typeOf(e) + "/" + key(e.getName())));
        int count = current.size();
        int created = 0;
        List<String> skipped = new ArrayList<>();
        for (LoreDraft draft : drafts) {
            String type = loreForm.requireType(draft.type());
            String name = EntityNames.require(draft.name(), "lore_invalid", "Dê um nome.");
            if (!existing.add(type + "/" + key(name))) {
                skipped.add(name);
                continue;
            }
            if (++count > MAX_LORE) {
                throw ApiException.unprocessable("too_much_lore", "Este universo chegou a %d itens.".formatted(MAX_LORE));
            }
            List<UUID> places = new ArrayList<>();
            for (String placeName : draft.placeNames() == null ? List.<String>of() : draft.placeNames()) {
                UUID id = placeByName.get(key(placeName));
                if (id != null && !places.contains(id)) {
                    places.add(id);
                }
            }
            entityRepository.save(UniverseEntity.builder()
                    .universeId(universeId)
                    .kind(UniverseEntity.LORE)
                    .name(name)
                    .attributes(attributes(type, places))
                    .appearance(jsonMapper.writeValueAsString(loreForm.normalize(type, draft.values(), jsonMapper)))
                    .build());
            created++;
        }
        log.info("Contexto do mundo no universo {}: {} criados, {} já existiam", universeId, created, skipped.size());
        return new LoreImport(created, skipped);
    }

    public String typeOf(UniverseEntity item) {
        return jsonMapper.readTree(item.getAttributes()).path("type").asString(null);
    }

    public List<UUID> placeIds(UniverseEntity item) {
        List<UUID> ids = new ArrayList<>();
        jsonMapper.readTree(item.getAttributes()).path("placeIds").forEach(n -> ids.add(UUID.fromString(n.stringValue())));
        return ids;
    }

    public JsonNode values(UniverseEntity item) {
        return jsonMapper.readTree(item.getAppearance());
    }

    /** Texto curto para a lista, a partir do campo de resumo do tipo. */
    public String summary(UniverseEntity item) {
        String field = loreForm.summaryField(typeOf(item));
        JsonNode value = field == null ? null : values(item).get(field);
        if (value == null || !value.isString()) {
            return null;
        }
        String text = value.stringValue();
        if (text.length() <= 140) {
            return text;
        }
        int cut = text.lastIndexOf(' ', 140);
        return text.substring(0, cut > 40 ? cut : 140).strip() + "…";
    }

    // ===== Internos =====

    private List<UUID> checkPlaces(UUID universeId, List<UUID> placeIds) {
        if (placeIds == null || placeIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> unique = new LinkedHashSet<>(placeIds);
        if (unique.size() > MAX_PLACES_PER_ITEM) {
            throw LoreForm.invalid("placeIds", "no máximo %d lugares".formatted(MAX_PLACES_PER_ITEM));
        }
        Set<UUID> places = new java.util.HashSet<>();
        entityRepository.findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(universeId, UniverseEntity.PLACE)
                .forEach(p -> places.add(p.getId()));
        for (UUID id : unique) {
            if (!places.contains(id)) {
                throw LoreForm.invalid("placeIds", "só lugares deste universo");
            }
        }
        return List.copyOf(unique);
    }

    private String attributes(String type, List<UUID> placeIds) {
        ObjectNode node = jsonMapper.createObjectNode().put("type", type);
        ArrayNode ids = node.putArray("placeIds");
        placeIds.forEach(id -> ids.add(id.toString()));
        return jsonMapper.writeValueAsString(node);
    }

    private List<UniverseEntity> lore(UUID universeId) {
        return entityRepository.findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(universeId, UniverseEntity.LORE);
    }

    private void requireUniverse(UUID ownerId, UUID universeId) {
        universeRepository.findByIdAndOwnerId(universeId, ownerId)
                .orElseThrow(() -> ApiException.notFound("universe_not_found", "Universo não encontrado."));
    }

    private UniverseEntity require(UUID ownerId, UUID loreId) {
        UniverseEntity item = entityRepository.findByIdAndKindAndDeletedAtIsNull(loreId, UniverseEntity.LORE)
                .orElseThrow(LoreService::notFound);
        if (universeRepository.findByIdAndOwnerId(item.getUniverseId(), ownerId).isEmpty()) {
            throw notFound();
        }
        return item;
    }

    private static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    private static ApiException notFound() {
        return ApiException.notFound("lore_not_found", "Ficha não encontrada.");
    }

    /** {@code custom}: valores dos campos próprios; nulo mantém os que estavam. */
    public record LoreData(String name, List<String> aliases, JsonNode values, List<UUID> placeIds, JsonNode custom) {
        public LoreData(String name, List<String> aliases, JsonNode values, List<UUID> placeIds) {
            this(name, aliases, values, placeIds, null);
        }
    }

    /** Um item do mundo gerado; {@code placeNames}: lugares (pelo nome) onde está presente. */
    public record LoreDraft(String type, String name, JsonNode values, List<String> placeNames) {
    }

    public record LoreImport(int created, List<String> skipped) {
    }
}
