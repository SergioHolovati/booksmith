package com.booksmith.assist.internal.illustration;

import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IllustrationPromptTest {

    private static final IllustrationPrompt.Style WATERCOLOR =
            new IllustrationPrompt.Style("watercolor", true, "warm", "medium", "plain");

    private static IllustrationPrompt.Person lia() {
        Map<String, String> traits = new LinkedHashMap<>();
        traits.put("coreValues", "Lealdade");          // não se vê: fica de fora
        traits.put("hairColor", "Ruivo");
        traits.put("eyeColor", "Verdes");
        traits.put("species", "Humana");
        traits.put("clothingStyle", "Jaqueta de couro gasta");
        traits.put("voiceTone", "Grave");               // não se vê
        return new IllustrationPrompt.Person("Lia", traits);
    }

    @Test
    void portraitIsShortOrderedAndOnlyVisualTraits() {
        String prompt = IllustrationPrompt.portrait(WATERCOLOR, lia(), "half", "smile", null);

        assertThat(prompt).isEqualTo("half-body portrait of Lia (humana, eyes verdes, hair ruivo, wearing jaqueta de couro gasta), "
                + "gentle smile. watercolor painting, soft washes, paper texture, warm palette, moderate detail, "
                + "plain solid background. single character, no text, no watermark.");
        assertThat(prompt).doesNotContain("Lealdade").doesNotContain("Grave");
        assertThat(prompt.length()).isLessThan(300);
    }

    @Test
    void traitsAreCappedSoOneCharacterNeverFloodsThePrompt() {
        Map<String, String> traits = new LinkedHashMap<>();
        for (String key : IllustrationPrompt.VISUAL_FIELDS.keySet()) {
            traits.put(key, "x".repeat(40));
        }
        String described = IllustrationPrompt.describe(new IllustrationPrompt.Person("Tomás", traits));

        assertThat(described.length()).isLessThanOrEqualTo("Tomás ()".length() + IllustrationPrompt.MAX_TRAITS_CHARS);
    }

    @Test
    void sceneLeadsWithTheSceneThenPeoplePlaceLightMoodAndStyle() {
        var place = new IllustrationPrompt.Place("Farol de Vel", "building",
                "Um farol de pedra na ponta do cais, com a tinta branca descascando e gaivotas no telhado.");
        String prompt = IllustrationPrompt.scene(new IllustrationPrompt.Style("ink", false, null, "rich", "scene"),
                "Lia sobe a escada do farol durante a tempestade", place, List.of(lia()), "storm", "tense", "low", null);

        assertThat(prompt).startsWith("Book illustration: Lia sobe a escada do farol durante a tempestade. Characters: Lia (");
        assertThat(prompt).contains("(match the reference images). Setting: Farol de Vel, a building: Um farol de pedra");
        assertThat(prompt).contains("stormy, dramatic light. tense, suspenseful mood. low-angle shot. ink and brush drawing");
        assertThat(prompt).contains("black and white, grayscale, for black-ink printing");
        assertThat(prompt).endsWith("no text, no watermark.");
    }

    @Test
    void longPlaceDescriptionsAreCutAtAWord() {
        var place = new IllustrationPrompt.Place("Cidade", "city", ("palavra ").repeat(60));

        String described = IllustrationPrompt.describe(place);

        assertThat(described.length()).isLessThan(IllustrationPrompt.MAX_PLACE_CHARS + 30);
        assertThat(described).endsWith("…");
    }

    @Test
    void coverAsksForArtworkWithoutLettersAndRoomForTheTitle() {
        String prompt = IllustrationPrompt.cover(WATERCOLOR, "O Farol", false, "um farol solitário sob a tempestade",
                List.of(), "mysterious", "névoa baixa");

        assertThat(prompt).startsWith("Book cover artwork: um farol solitário sob a tempestade. mysterious, shadowy mood. vertical composition");
        assertThat(prompt).contains("empty area in the top third for the title");
        assertThat(prompt).contains("névoa baixa");
        assertThat(prompt).endsWith("no text, no letters, no title, no watermark.");
    }

    @Test
    void unknownOptionsAndEmptyTextsAreRefused() {
        assertThatThrownBy(() -> IllustrationPrompt.portrait(WATERCOLOR, lia(), "selfie", "smile", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("illustration_invalid"));
        assertThatThrownBy(() -> IllustrationPrompt.scene(WATERCOLOR, "  ", null, List.of(), null, null, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("illustration_invalid"));
        assertThatThrownBy(() -> IllustrationPrompt.scene(WATERCOLOR, "cena", null, List.of(), "midnight", null, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("illustration_invalid"));
        assertThatThrownBy(() -> IllustrationPrompt.cover(WATERCOLOR, "t", false, "x".repeat(301), List.of(), null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("illustration_invalid"));
    }
}
