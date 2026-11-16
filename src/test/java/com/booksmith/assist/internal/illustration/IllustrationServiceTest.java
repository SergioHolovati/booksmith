package com.booksmith.assist.internal.illustration;

import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.api.Manuscripts;
import com.booksmith.universe.api.Universes;
import com.booksmith.universe.api.Universes.KnownEntity;
import com.booksmith.universe.api.Universes.Trait;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IllustrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-14T15:00:00Z");
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID UNIVERSE = UUID.randomUUID();
    private static final UUID LIA = UUID.randomUUID();
    private static final UUID TOMAS = UUID.randomUUID();
    private static final UUID FAROL = UUID.randomUUID();
    private static final UUID BOOK = UUID.randomUUID();

    private final ImageModel model = mock(ImageModel.class);
    private final ImageGenerationRepository generations = mock(ImageGenerationRepository.class);
    private final Universes universes = mock(Universes.class);
    private final Manuscripts manuscripts = mock(Manuscripts.class);
    private final List<ImageGeneration> saved = new ArrayList<>();
    private IllustrationService service;

    @BeforeEach
    void setUp() {
        when(model.provider()).thenReturn("flux");
        when(model.model()).thenReturn("flux-2-pro");
        when(generations.save(any())).thenAnswer(i -> {
            saved.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(generations.findAllByUserIdAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(eq(OWNER), eq("ready"), any()))
                .thenAnswer(i -> saved.stream().filter(g -> "ready".equals(g.getStatus())).toList());
        Map<String, Trait> appearance = new LinkedHashMap<>();
        appearance.put("eyeColor", new Trait("green", "Verdes"));
        appearance.put("hairColor", new Trait("red", "Ruivo"));
        appearance.put("coreValues", new Trait("loyalty", "Lealdade"));
        when(universes.entities(UNIVERSE)).thenReturn(List.of(
                new KnownEntity(LIA, "character", "Lia", List.of(), appearance),
                new KnownEntity(TOMAS, "character", "Tomás", List.of(), Map.of()),
                new KnownEntity(FAROL, "place", "Farol de Vel", List.of(), Map.of())));
        when(universes.portraitReference(UNIVERSE, LIA)).thenReturn(Optional.of("https://booksmith.com.br/media/lia.jpg"));
        when(universes.portraitReference(UNIVERSE, TOMAS)).thenReturn(Optional.empty());
        when(universes.placeBrief(UNIVERSE, FAROL)).thenReturn(Optional.of(
                new Universes.PlaceBrief(FAROL, "Farol de Vel", "building", "Farol de pedra na ponta do cais.")));
        when(universes.addGeneratedImage(eq(OWNER), eq(UNIVERSE), any(), anyString(), anyString(), anyList(), any()))
                .thenAnswer(i -> new Universes.GeneratedImage(UUID.randomUUID(), "/media/x.jpg", i.getArgument(4)));
        when(model.generate(anyString(), anyInt(), anyInt(), anyList()))
                .thenReturn(new ImageModel.Generated(new byte[] {1}, "image/jpeg", 3.0));
        when(manuscripts.bookBrief(OWNER, BOOK)).thenReturn(Optional.of(
                new Manuscripts.BookBrief(BOOK, "O Farol", "sinopse", UNIVERSE, false, true)));
        service = new IllustrationService(model, new IllustrationProperties("flux", 3, 2, Duration.ofSeconds(30),
                new IllustrationProperties.Flux("chave", null, null, 2)), generations, universes, manuscripts,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static IllustrationService.Request portrait(Boolean setAsPortrait) {
        return new IllustrationService.Request("portrait", UNIVERSE, LIA, null, null, null, "half", "smile",
                "watercolor", true, "warm", "medium", "plain", null, setAsPortrait, null, null, null, null, null, null);
    }

    private static IllustrationService.Request scene(List<UUID> who, UUID placeId, String text) {
        return new IllustrationService.Request("scene", UNIVERSE, null, who, text, "opening", null, null,
                "ink", false, null, "rich", "scene", null, null, placeId, "storm", "tense", "low", null, null);
    }

    private static IllustrationService.Request cover(UUID bookId, String subject) {
        return new IllustrationService.Request("cover", null, null, List.of(LIA), null, null, null, null,
                "digital", true, "cool", "rich", "scene", null, null, null, null, "mysterious", null, bookId, subject);
    }

    @Test
    void portraitUsesOnlyTheVisualTraitsAndTheCurrentPortraitAsReference() {
        var result = service.generate(OWNER, portrait(true));

        verify(model).generate(argThat(p -> p.startsWith("half-body portrait of Lia (eyes verdes, hair ruivo), gentle smile.")
                        && !p.contains("Lealdade") && p.contains("watercolor")),
                eq(896), eq(1152), eq(List.of("https://booksmith.com.br/media/lia.jpg")));
        verify(universes).addGeneratedImage(eq(OWNER), eq(UNIVERSE), any(), eq("image/jpeg"), eq("Retrato de Lia (IA)"),
                eq(List.of(LIA)), eq(LIA));
        assertThat(result.portrait()).isTrue();
        assertThat(result.remaining()).isEqualTo(2);
        assertThat(saved).singleElement().satisfies(g -> assertThat(g.getStatus()).isEqualTo("ready"));
    }

    @Test
    void sceneSendsThePlaceThePortraitsLightMoodShotAndFormat() {
        var result = service.generate(OWNER, scene(List.of(LIA, TOMAS), FAROL, "Lia e Tomás no farol"));

        verify(model).generate(argThat(p -> p.startsWith("Book illustration: Lia e Tomás no farol. Characters: Lia (")
                        && p.contains("Setting: Farol de Vel, a building: Farol de pedra na ponta do cais.")
                        && p.contains("stormy, dramatic light. tense, suspenseful mood. low-angle shot")
                        && p.contains("black and white")),
                eq(1216), eq(832), eq(List.of("https://booksmith.com.br/media/lia.jpg")));
        verify(universes).addGeneratedImage(eq(OWNER), eq(UNIVERSE), any(), anyString(), eq("Lia e Tomás no farol (IA)"),
                eq(List.of(LIA, TOMAS, FAROL)), eq(null));
        assertThat(result.portrait()).isFalse();
    }

    @Test
    void coverOfACompleteBookGoesToTheBooksUniverseWithoutLetters() {
        var result = service.generate(OWNER, cover(BOOK, "um farol solitário sob a tempestade"));

        verify(model).generate(argThat(p -> p.startsWith("Book cover artwork: um farol solitário sob a tempestade. Characters: Lia (")
                        && p.contains("top third for the title") && p.endsWith("no text, no letters, no title, no watermark.")),
                eq(800), eq(1280), eq(List.of("https://booksmith.com.br/media/lia.jpg")));
        assertThat(result.caption()).isEqualTo("Arte da capa de O Farol (IA)");
    }

    @Test
    void coverNeedsAFinishedBookOfTheOwner() {
        when(manuscripts.bookBrief(OWNER, BOOK)).thenReturn(Optional.of(
                new Manuscripts.BookBrief(BOOK, "O Farol", null, UNIVERSE, false, false)));
        assertThatThrownBy(() -> service.generate(OWNER, cover(BOOK, "farol")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("book_not_complete"));

        UUID other = UUID.randomUUID();
        when(manuscripts.bookBrief(OWNER, other)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.generate(OWNER, cover(other, "farol")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("book_not_found"));
        verify(model, never()).generate(anyString(), anyInt(), anyInt(), anyList());
    }

    @Test
    void threePerWeekThenItWaits() {
        service.generate(OWNER, portrait(false));
        service.generate(OWNER, portrait(false));
        service.generate(OWNER, portrait(false));

        assertThatThrownBy(() -> service.generate(OWNER, portrait(false)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("illustration_weekly_limit"));
        assertThat(service.status(OWNER).remaining()).isZero();
    }

    @Test
    void aModeratedRequestIsRecordedButDoesNotCount() {
        when(model.generate(anyString(), anyInt(), anyInt(), anyList())).thenThrow(FluxClient.moderated());

        assertThatThrownBy(() -> service.generate(OWNER, portrait(false)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("illustration_moderated"));
        assertThat(saved).singleElement().satisfies(g -> assertThat(g.getStatus()).isEqualTo("moderated"));
        assertThat(service.status(OWNER).remaining()).isEqualTo(3);
        verify(universes, never()).addGeneratedImage(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void invalidOptionsUnknownPlacesAndOtherPeoplesCharactersAreRefusedBeforeGenerating() {
        var badStyle = new IllustrationService.Request("portrait", UNIVERSE, LIA, null, null, null, "half", "smile",
                "photoreal", true, "warm", "medium", "plain", null, false, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.generate(OWNER, badStyle))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("illustration_invalid"));
        assertThatThrownBy(() -> service.generate(OWNER, scene(List.of(UUID.randomUUID()), null, "uma cena")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("illustration_invalid"));
        assertThatThrownBy(() -> service.generate(OWNER, scene(List.of(), UUID.randomUUID(), "uma cena")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("illustration_invalid"));
        verify(model, never()).generate(anyString(), anyInt(), anyInt(), anyList());
    }

    @Test
    void withoutAKeyItIsOff() {
        var off = new IllustrationService(model, new IllustrationProperties("flux", 3, 2, null, null), generations,
                universes, manuscripts, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(off.status(OWNER).enabled()).isFalse();
        assertThatThrownBy(() -> off.generate(OWNER, portrait(false)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("illustrations_disabled"));
    }
}
