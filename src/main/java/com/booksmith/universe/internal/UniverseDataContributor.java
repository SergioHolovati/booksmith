package com.booksmith.universe.internal;

import com.booksmith.common.data.PersonalDataArchive;
import com.booksmith.common.data.PersonalDataContributor;
import com.booksmith.media.api.ImageStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Baixar tudo": cada universo numa pasta, com o construtor, o calendário, personagens, lugares, fichas do mundo,
 * linha do tempo, relações, o mapa e a biblioteca de imagens (os arquivos e quem aparece em cada um).
 */
@Component
@RequiredArgsConstructor
class UniverseDataContributor implements PersonalDataContributor {

    private final UniverseRepository universeRepository;
    private final UniverseEntityRepository entityRepository;
    private final TimelineEventRepository eventRepository;
    private final RelationRepository relationRepository;
    private final UniverseImageRepository imageRepository;
    private final ImageStorage imageStorage;
    private final JsonMapper jsonMapper;

    @Override
    public String name() {
        return "universos";
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(UUID ownerId, PersonalDataArchive archive) {
        for (Universe universe : universeRepository.findAllByOwnerIdOrderByNameAsc(ownerId)) {
            String folder = "universos/" + PersonalDataArchive.folder(universe.getName(), universe.getId()) + "/";
            UUID id = universe.getId();

            // Imagens primeiro: os retratos das fichas apontam para os arquivos
            Map<UUID, String> imageFiles = new HashMap<>();
            List<ImageData> images = imageRepository.findAllByUniverseIdAndDeletedAtIsNullOrderByCreatedAtDesc(id)
                    .stream()
                    .map(image -> {
                        String file = save(archive, folder + "imagens/" + image.getId(), image.getUrl());
                        if (file != null) {
                            imageFiles.put(image.getId(), file.substring(folder.length()));
                        }
                        return new ImageData(image.getId(), imageFiles.get(image.getId()), image.getCaption(),
                                List.of(image.getEntityIds()), image.getCreatedAt());
                    })
                    .toList();

            String map = save(archive, folder + "mapa", universe.getMapUrl());
            archive.json(folder + "universo.json", new UniverseData(id, universe.getName(), tree(universe.getDetails()),
                    tree(universe.getCalendar()), tree(universe.getMapGenerator()),
                    map == null ? null : map.substring(folder.length()), universe.getCreatedAt(), universe.getUpdatedAt()));

            archive.json(folder + "personagens.json", entities(id, UniverseEntity.CHARACTER, imageFiles));
            archive.json(folder + "lugares.json", entities(id, UniverseEntity.PLACE, imageFiles));
            archive.json(folder + "mundo.json", entities(id, UniverseEntity.LORE, imageFiles));
            archive.json(folder + "linha-do-tempo.json", eventRepository
                    .findAllByUniverseIdAndDeletedAtIsNullOrderBySortKeyAsc(id).stream()
                    .map(e -> new EventData(e.getId(), e.getTitle(), e.getKind(), e.getWhenLabel(), tree(e.getWorldDate()),
                            e.getDescription(), e.getChapterId(), e.getPlaceId(), List.of(e.getCharacterIds()),
                            List.of(e.getLoreIds()), e.getSortKey()))
                    .toList());
            archive.json(folder + "relacoes.json", relationRepository.findAllByUniverseIdAndDeletedAtIsNull(id).stream()
                    .map(r -> new RelationData(r.getId(), r.getFromId(), r.getToId(), r.getKind(), r.getDescription(),
                            r.getSinceEventId(), r.getUntilEventId()))
                    .toList());
            archive.json(folder + "imagens.json", images);
        }
    }

    private List<EntityData> entities(UUID universeId, String kind, Map<UUID, String> imageFiles) {
        return entityRepository.findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(universeId, kind).stream()
                .map(e -> new EntityData(e.getId(), e.getName(), Arrays.asList(e.getAliases()), tree(e.getAttributes()),
                        tree(e.getAppearance()), e.getMapX(), e.getMapY(),
                        e.getPortraitId() == null ? null : imageFiles.get(e.getPortraitId()), e.getUpdatedAt()))
                .toList();
    }

    /** Grava a imagem (o caminho ganha a extensão certa) e devolve o caminho, ou null se não deu para ler. */
    private String save(PersonalDataArchive archive, String pathWithoutExtension, String url) {
        if (url == null) {
            return null;
        }
        return imageStorage.read(url).map(image -> {
            String path = pathWithoutExtension + "." + image.extension();
            archive.bytes(path, image.bytes());
            return path;
        }).orElse(null);
    }

    private JsonNode tree(String json) {
        return json == null || json.isBlank() ? null : jsonMapper.readTree(json);
    }

    /** {@code map}: arquivo do mapa na pasta do universo. */
    record UniverseData(UUID id, String name, JsonNode details, JsonNode calendar, JsonNode mapGenerator, String map,
                        Instant createdAt, Instant updatedAt) {
    }

    /**
     * {@code sheet}: ficha (papel, idade, tipo do lugar, "dentro de"...); {@code values}: aparência e personalidade,
     * ou os campos da ficha do mundo; {@code portrait}: arquivo do retrato.
     */
    record EntityData(UUID id, String name, List<String> aliases, JsonNode sheet, JsonNode values, Float mapX,
                      Float mapY, String portrait, Instant updatedAt) {
    }

    record EventData(UUID id, String title, String kind, String when, JsonNode worldDate, String description,
                     UUID chapterId, UUID placeId, List<UUID> characterIds, List<UUID> loreIds, BigDecimal order) {
    }

    record RelationData(UUID id, UUID fromId, UUID toId, String kind, String description, UUID sinceEventId,
                        UUID untilEventId) {
    }

    /** {@code file}: arquivo na pasta do universo; {@code entityIds}: quem aparece na imagem. */
    record ImageData(UUID id, String file, String caption, List<UUID> entityIds, Instant createdAt) {
    }
}
