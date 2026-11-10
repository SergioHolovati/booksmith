package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.media.api.ImageCategory;
import com.booksmith.media.api.ImageStorage;
import com.booksmith.media.api.StoredImage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Biblioteca de imagens do universo (§4.4 do produto): retratos, referências visuais, fotos de lugares. Cada
 * imagem pode estar ligada a personagens, lugares e fichas do mundo; um personagem ou lugar pode ter uma delas
 * como retrato. Excluir manda para a lixeira do universo; o arquivo só sai do armazenamento no expurgo, 30 dias
 * depois. Tudo é do dono do universo: de outra pessoa, responde 404.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GalleryService {

    static final int MAX_IMAGES = 500;
    static final int MAX_LINKS = 50;
    static final int MAX_CAPTION = 500;
    private static final Set<String> LINKABLE = Set.of(UniverseEntity.CHARACTER, UniverseEntity.PLACE, UniverseEntity.LORE);
    private static final Set<String> WITH_PORTRAIT = Set.of(UniverseEntity.CHARACTER, UniverseEntity.PLACE);

    private final UniverseRepository universeRepository;
    private final UniverseEntityRepository entityRepository;
    private final UniverseImageRepository imageRepository;
    private final ImageStorage imageStorage;
    private final Clock clock;

    /** Imagens do universo, mais novas primeiro; com {@code entityId}, só as ligadas a ele. */
    @Transactional(readOnly = true)
    public List<UniverseImage> list(UUID ownerId, UUID universeId, UUID entityId) {
        requireUniverse(ownerId, universeId);
        List<UniverseImage> all = imageRepository.findAllByUniverseIdAndDeletedAtIsNullOrderByCreatedAtDesc(universeId);
        return entityId == null ? all : all.stream().filter(i -> Arrays.asList(i.getEntityIds()).contains(entityId)).toList();
    }

    @Transactional
    public UniverseImage upload(UUID ownerId, UUID universeId, String dataUrl, String caption, List<UUID> entityIds) {
        requireUniverse(ownerId, universeId);
        if (imageRepository.countByUniverseIdAndDeletedAtIsNull(universeId) >= MAX_IMAGES) {
            throw ApiException.unprocessable("too_many_images",
                    "Este universo chegou a %d imagens. Exclua alguma para enviar outra.".formatted(MAX_IMAGES));
        }
        UUID[] links = checkLinks(universeId, entityIds);
        String text = checkCaption(caption);
        StoredImage stored = imageStorage.store(ImageCategory.GALLERY, ownerId, dataUrl);
        return imageRepository.save(UniverseImage.builder()
                .universeId(universeId)
                .url(stored.url())
                .contentType(stored.contentType())
                .bytes(stored.bytes())
                .caption(text)
                .entityIds(links)
                .build());
    }

    /** Legenda e ligações. {@code expectedVersion} desatualizada → 409. */
    @Transactional
    public UniverseImage update(UUID ownerId, UUID imageId, long expectedVersion, String caption, List<UUID> entityIds) {
        UniverseImage image = require(ownerId, imageId);
        if (image.getVersion() != expectedVersion) {
            throw ApiException.conflict("image_version_conflict", "Esta imagem foi alterada em outro aparelho.",
                    Map.of("currentVersion", image.getVersion()));
        }
        image.setCaption(checkCaption(caption));
        image.setEntityIds(checkLinks(image.getUniverseId(), entityIds));
        return imageRepository.saveAndFlush(image);
    }

    @Transactional
    public void delete(UUID ownerId, UUID imageId) {
        require(ownerId, imageId).setDeletedAt(Instant.now(clock));
    }

    /**
     * Usa a imagem como retrato do personagem ou lugar (e liga a imagem a ele, se ainda não estava).
     * {@code imageId} null tira o retrato.
     */
    @Transactional
    public UniverseEntity setPortrait(UUID ownerId, UUID entityId, UUID imageId) {
        UniverseEntity entity = entityRepository.findById(entityId)
                .filter(e -> e.getDeletedAt() == null && WITH_PORTRAIT.contains(e.getKind()))
                .orElseThrow(() -> ApiException.notFound("entity_not_found", "Personagem ou lugar não encontrado."));
        requireUniverse(ownerId, entity.getUniverseId());
        if (imageId == null) {
            entity.setPortraitId(null);
            return entity;
        }
        UniverseImage image = require(ownerId, imageId);
        if (!image.getUniverseId().equals(entity.getUniverseId())) {
            throw ApiException.unprocessable("image_invalid", "A imagem precisa ser deste universo.");
        }
        Set<UUID> links = new LinkedHashSet<>(Arrays.asList(image.getEntityIds()));
        if (links.add(entityId)) {
            image.setEntityIds(links.toArray(UUID[]::new));
        }
        entity.setPortraitId(imageId);
        return entity;
    }

    /** URL do retrato de cada personagem e lugar do universo que tem um (imagens excluídas não contam). */
    @Transactional(readOnly = true)
    public Map<UUID, String> portraits(UUID universeId) {
        Map<UUID, String> urls = new HashMap<>();
        imageRepository.findAllByUniverseIdAndDeletedAtIsNullOrderByCreatedAtDesc(universeId)
                .forEach(i -> urls.put(i.getId(), i.getUrl()));
        Map<UUID, String> portraits = new HashMap<>();
        for (String kind : WITH_PORTRAIT) {
            for (UniverseEntity e : entityRepository.findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(universeId, kind)) {
                if (e.getPortraitId() != null && urls.containsKey(e.getPortraitId())) {
                    portraits.put(e.getId(), urls.get(e.getPortraitId()));
                }
            }
        }
        return portraits;
    }

    /** URL do retrato de uma entidade, ou null. */
    @Transactional(readOnly = true)
    public String portrait(UniverseEntity entity) {
        if (entity.getPortraitId() == null) {
            return null;
        }
        return imageRepository.findByIdAndDeletedAtIsNull(entity.getPortraitId()).map(UniverseImage::getUrl).orElse(null);
    }

    /** Nome e tipo das entidades vivas ligadas às imagens, para a resposta. */
    @Transactional(readOnly = true)
    public Map<UUID, UniverseEntity> linkable(UUID universeId) {
        Map<UUID, UniverseEntity> map = new HashMap<>();
        for (String kind : LINKABLE) {
            entityRepository.findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(universeId, kind)
                    .forEach(e -> map.put(e.getId(), e));
        }
        return map;
    }

    // ===== Validação =====

    private UUID[] checkLinks(UUID universeId, List<UUID> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) {
            return new UUID[0];
        }
        Set<UUID> unique = new LinkedHashSet<>(entityIds);
        unique.remove(null);
        if (unique.size() > MAX_LINKS) {
            throw ApiException.unprocessable("image_invalid", "Uma imagem pode ter até %d ligações.".formatted(MAX_LINKS));
        }
        Map<UUID, UniverseEntity> alive = linkable(universeId);
        if (!alive.keySet().containsAll(unique)) {
            throw ApiException.unprocessable("image_invalid",
                    "Só dá para ligar a imagem a personagens, lugares e fichas deste universo.");
        }
        return unique.toArray(UUID[]::new);
    }

    private static String checkCaption(String caption) {
        String text = caption == null ? "" : caption.strip();
        if (text.length() > MAX_CAPTION) {
            throw ApiException.unprocessable("image_invalid", "A legenda passou de %d caracteres.".formatted(MAX_CAPTION));
        }
        return text.isEmpty() ? null : text;
    }

    private void requireUniverse(UUID ownerId, UUID universeId) {
        universeRepository.findByIdAndOwnerId(universeId, ownerId)
                .orElseThrow(() -> ApiException.notFound("universe_not_found", "Universo não encontrado."));
    }

    private UniverseImage require(UUID ownerId, UUID imageId) {
        UniverseImage image = imageRepository.findByIdAndDeletedAtIsNull(imageId).orElseThrow(GalleryService::notFound);
        if (universeRepository.findByIdAndOwnerId(image.getUniverseId(), ownerId).isEmpty()) {
            throw notFound();
        }
        return image;
    }

    private static ApiException notFound() {
        return ApiException.notFound("image_not_found", "Imagem não encontrada.");
    }
}
