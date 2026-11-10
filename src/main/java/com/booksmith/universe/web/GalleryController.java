package com.booksmith.universe.web;

import com.booksmith.identity.api.CurrentUser;
import com.booksmith.universe.internal.GalleryService;
import com.booksmith.universe.internal.UniverseEntity;
import com.booksmith.universe.internal.UniverseImage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Biblioteca de imagens do universo (§4.4 do produto) e retratos de personagens e lugares. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
class GalleryController {

    private final GalleryService galleryService;
    private final CurrentUser currentUser;

    /** Com {@code entityId}, só as imagens ligadas àquele personagem, lugar ou ficha. */
    @GetMapping("/universes/{universeId}/images")
    List<ImageResponse> list(@PathVariable UUID universeId, @RequestParam(required = false) UUID entityId) {
        List<UniverseImage> images = galleryService.list(currentUser.id(), universeId, entityId);
        Map<UUID, UniverseEntity> entities = galleryService.linkable(universeId);
        return images.stream().map(i -> response(i, entities)).toList();
    }

    /** {@code dataUrl}: PNG, JPEG ou WebP em base64, até 5 MB (o app reduz a foto antes de enviar). */
    @PostMapping("/universes/{universeId}/images")
    @ResponseStatus(HttpStatus.CREATED)
    ImageResponse upload(@PathVariable UUID universeId, @RequestBody @Valid UploadRequest request) {
        UniverseImage image = galleryService.upload(currentUser.id(), universeId, request.dataUrl(), request.caption(),
                request.entityIds());
        return response(image, galleryService.linkable(universeId));
    }

    @PutMapping("/images/{imageId}")
    ImageResponse update(@PathVariable UUID imageId, @RequestBody @Valid UpdateRequest request) {
        UniverseImage image = galleryService.update(currentUser.id(), imageId, request.version(), request.caption(),
                request.entityIds());
        return response(image, galleryService.linkable(image.getUniverseId()));
    }

    @DeleteMapping("/images/{imageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID imageId) {
        galleryService.delete(currentUser.id(), imageId);
    }

    /** Retrato de um personagem ou lugar; {@code imageId} null tira o retrato. */
    @PutMapping("/entities/{entityId}/portrait")
    PortraitResponse portrait(@PathVariable UUID entityId, @RequestBody PortraitRequest request) {
        UniverseEntity entity = galleryService.setPortrait(currentUser.id(), entityId, request.imageId());
        return new PortraitResponse(entity.getId(), entity.getPortraitId(), galleryService.portrait(entity));
    }

    private static ImageResponse response(UniverseImage image, Map<UUID, UniverseEntity> entities) {
        List<Linked> linked = Arrays.stream(image.getEntityIds())
                .map(entities::get)
                .filter(Objects::nonNull)
                .map(e -> new Linked(e.getId(), e.getName(), e.getKind()))
                .toList();
        return new ImageResponse(image.getId(), image.getUniverseId(), image.getUrl(), image.getContentType(),
                image.getBytes(), image.getCaption(), linked, image.getVersion(), image.getCreatedAt());
    }

    record UploadRequest(@NotBlank(message = "Escolha uma imagem.") String dataUrl, String caption,
                         List<UUID> entityIds) {
    }

    record UpdateRequest(@NotNull Long version, String caption, List<UUID> entityIds) {
    }

    record PortraitRequest(UUID imageId) {
    }

    record PortraitResponse(UUID entityId, UUID imageId, String url) {
    }

    /** {@code kind}: character, place ou lore. */
    record Linked(UUID id, String name, String kind) {
    }

    record ImageResponse(UUID id, UUID universeId, String url, String contentType, long bytes, String caption,
                         List<Linked> entities, long version, Instant createdAt) {
    }
}
