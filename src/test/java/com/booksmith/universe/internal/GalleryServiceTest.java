package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.media.api.ImageCategory;
import com.booksmith.media.api.ImageStorage;
import com.booksmith.media.api.StoredImage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GalleryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    private final UniverseRepository universes = mock(UniverseRepository.class);
    private final UniverseEntityRepository entities = mock(UniverseEntityRepository.class);
    private final UniverseImageRepository images = mock(UniverseImageRepository.class);
    private final ImageStorage storage = mock(ImageStorage.class);
    private final GalleryService service = new GalleryService(universes, entities, images, storage,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID owner = UUID.randomUUID();
    private final UUID universeId = UUID.randomUUID();
    private final UniverseEntity lia = entity("Lia", UniverseEntity.CHARACTER);

    GalleryServiceTest() {
        when(universes.findByIdAndOwnerId(universeId, owner))
                .thenReturn(Optional.of(Universe.builder().id(universeId).ownerId(owner).name("Vel").build()));
        when(entities.findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(universeId, UniverseEntity.CHARACTER))
                .thenReturn(List.of(lia));
        when(images.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void uploadStoresTheFileAndLinksIt() {
        when(storage.store(eq(ImageCategory.GALLERY), eq(owner), any()))
                .thenReturn(new StoredImage("gallery/x.webp", "/media/gallery/x.webp", "image/webp", 1234));

        UniverseImage image = service.upload(owner, universeId, "data:image/webp;base64,AAAA", "  Lia na chuva  ",
                List.of(lia.getId(), lia.getId()));

        assertThat(image.getUrl()).isEqualTo("/media/gallery/x.webp");
        assertThat(image.getCaption()).isEqualTo("Lia na chuva");
        assertThat(image.getEntityIds()).containsExactly(lia.getId());
    }

    @Test
    void linksOnlyToThingsOfThisUniverseAndCheckedBeforeStoring() {
        assertThatThrownBy(() -> service.upload(owner, universeId, "data:image/png;base64,AAAA", null,
                List.of(UUID.randomUUID())))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("image_invalid"));
        verify(storage, never()).store(any(), any(), any());
    }

    @Test
    void portraitLinksTheImageToTheCharacter() {
        UniverseImage image = UniverseImage.builder().id(UUID.randomUUID()).universeId(universeId).url("/media/a.png")
                .contentType("image/png").build();
        when(images.findByIdAndDeletedAtIsNull(image.getId())).thenReturn(Optional.of(image));
        when(entities.findById(lia.getId())).thenReturn(Optional.of(lia));

        service.setPortrait(owner, lia.getId(), image.getId());

        assertThat(lia.getPortraitId()).isEqualTo(image.getId());
        assertThat(image.getEntityIds()).containsExactly(lia.getId());
        assertThat(service.portraits(universeId)).isEmpty(); // lista de imagens vivas vazia no mock: sem retrato
    }

    @Test
    void someoneElsesImageIsNotFound() {
        UniverseImage image = UniverseImage.builder().id(UUID.randomUUID()).universeId(UUID.randomUUID())
                .url("/media/a.png").contentType("image/png").build();
        when(images.findByIdAndDeletedAtIsNull(image.getId())).thenReturn(Optional.of(image));

        assertThatThrownBy(() -> service.delete(owner, image.getId()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("image_not_found"));
    }

    private UniverseEntity entity(String name, String kind) {
        return UniverseEntity.builder().id(UUID.randomUUID()).universeId(universeId).kind(kind).name(name).build();
    }
}
