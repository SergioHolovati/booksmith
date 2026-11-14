package com.booksmith.assist.internal.illustration;

import com.booksmith.common.error.ApiException;
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

    private final ImageModel model = mock(ImageModel.class);
    private final ImageGenerationRepository generations = mock(ImageGenerationRepository.class);
    private final Universes universes = mock(Universes.class);
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
        when(universes.entities(UNIVERSE)).thenReturn(List.of(
                new KnownEntity(LIA, "character", "Lia", List.of(), appearance),
                new KnownEntity(TOMAS, "character", "Tomás", List.of(), Map.of())));
        when(universes.portraitReference(UNIVERSE, LIA)).thenReturn(Optional.of("https://booksmith.com.br/media/lia.jpg"));
        when(universes.portraitReference(UNIVERSE, TOMAS)).thenReturn(Optional.empty());
        when(universes.addGeneratedImage(eq(OWNER), eq(UNIVERSE), any(), anyString(), anyString(), anyList(), any()))
                .thenAnswer(i -> new Universes.GeneratedImage(UUID.randomUUID(), "/media/x.jpg", i.getArgument(4)));
        when(model.generate(anyString(), anyInt(), anyInt(), anyList()))
                .thenReturn(new ImageModel.Generated(new byte[] {1}, "image/jpeg", 3.0));
        service = new IllustrationService(model, new IllustrationProperties("flux", 3, 2, Duration.ofSeconds(30),
                new IllustrationProperties.Flux("chave", null, null, 2)), generations, universes,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static IllustrationService.Request portrait(Boolean setAsPortrait) {
        return new IllustrationService.Request("portrait", UNIVERSE, LIA, null, null, null, "half", "smile",
                "watercolor", true, "warm", "medium", "plain", null, setAsPortrait);
    }

    @Test
    void portraitUsesTheAppearanceAndTheCurrentPortraitAsReference() {
        var result = service.generate(OWNER, portrait(true));

        verify(model).generate(org.mockito.ArgumentMatchers.argThat(p -> p.contains("Lia (eye color: Verdes, hair color: Ruivo)")
                        && p.contains("watercolor") && p.contains("half body portrait")),
                eq(896), eq(1152), eq(List.of("https://booksmith.com.br/media/lia.jpg")));
        verify(universes).addGeneratedImage(eq(OWNER), eq(UNIVERSE), any(), eq("image/jpeg"), eq("Retrato de Lia (IA)"),
                eq(List.of(LIA)), eq(LIA));
        assertThat(result.portrait()).isTrue();
        assertThat(result.remaining()).isEqualTo(2);
        assertThat(saved).singleElement().satisfies(g -> assertThat(g.getStatus()).isEqualTo("ready"));
    }

    @Test
    void sceneSendsTheCharactersPortraitsAndTheChosenFormat() {
        var request = new IllustrationService.Request("scene", UNIVERSE, null, List.of(LIA, TOMAS),
                "Lia e Tomás no farol, durante a tempestade", "opening", null, null, "ink", false, null, "rich",
                "scene", null, null);

        var result = service.generate(OWNER, request);

        verify(model).generate(org.mockito.ArgumentMatchers.argThat(p -> p.contains("no farol") && p.contains("black and white")),
                eq(1216), eq(832), eq(List.of("https://booksmith.com.br/media/lia.jpg")));
        assertThat(result.portrait()).isFalse();
        assertThat(result.caption()).isEqualTo("Lia e Tomás no farol, durante a tempestade (IA)");
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
    void invalidOptionsAndOtherPeoplesCharactersAreRefusedBeforeGenerating() {
        var badStyle = new IllustrationService.Request("portrait", UNIVERSE, LIA, null, null, null, "half", "smile",
                "photoreal", true, "warm", "medium", "plain", null, false);
        assertThatThrownBy(() -> service.generate(OWNER, badStyle))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("illustration_invalid"));

        var stranger = new IllustrationService.Request("scene", UNIVERSE, null, List.of(UUID.randomUUID()), "uma cena",
                "page", null, null, "ink", true, "warm", "medium", "scene", null, null);
        assertThatThrownBy(() -> service.generate(OWNER, stranger))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("illustration_invalid"));

        verify(model, never()).generate(anyString(), anyInt(), anyInt(), anyList());
    }

    @Test
    void withoutAKeyItIsOff() {
        var off = new IllustrationService(model, new IllustrationProperties("flux", 3, 2, null, null), generations,
                universes, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(off.status(OWNER).enabled()).isFalse();
        assertThatThrownBy(() -> off.generate(OWNER, portrait(false)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("illustrations_disabled"));
    }
}
