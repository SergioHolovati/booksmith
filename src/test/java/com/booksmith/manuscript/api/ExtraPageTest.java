package com.booksmith.manuscript.api;

import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtraPageTest {

    private static final String IMAGE = "11111111-1111-1111-1111-111111111111";
    private static final String CHAPTER = "22222222-2222-2222-2222-222222222222";

    private static ExtraPage art(String id, String after) {
        return new ExtraPage("illustration", after, IMAGE, null, id, null);
    }

    @Test
    void anIllustrationPageNeedsItsImageAndItsOwnId() {
        assertThatThrownBy(() -> new ExtraPage("illustration", "end", null, null, "33333333-3333-3333-3333-333333333333", null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new ExtraPage("illustration", "end", IMAGE, null, null, null))
                .isInstanceOf(ApiException.class);
        // "Antes do primeiro capítulo" só vale para ela
        assertThat(art("33333333-3333-3333-3333-333333333333", "front").after()).isEqualTo("front");
        assertThatThrownBy(() -> new ExtraPage("dedication", "front", null, null))
                .isInstanceOf(ApiException.class);
        // Sem dizer, inteira dentro das margens; a cor não vale para ela
        ExtraPage page = new ExtraPage("illustration", "end", IMAGE, "#ffffff", "33333333-3333-3333-3333-333333333333", null);
        assertThat(page.bleed()).isFalse();
        assertThat(page.color()).isNull();
    }

    @Test
    void manyIllustrationPagesButEachOnce() {
        String a = "33333333-3333-3333-3333-333333333333";
        String b = "44444444-4444-4444-4444-444444444444";
        assertThat(ExtraPage.validated(List.of(art(a, "start"), art(b, "start"),
                new ExtraPage("dedication", "start", null, null)))).hasSize(3);
        assertThatThrownBy(() -> ExtraPage.validated(List.of(art(a, "start"), art(a, "end"))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void anIllustrationPageAfterAChapterThatLeftGoesToTheEnd() {
        String a = "33333333-3333-3333-3333-333333333333";
        String b = "44444444-4444-4444-4444-444444444444";
        List<ExtraPage> arranged = ExtraPage.arrange(List.of(art(a, CHAPTER), art(b, CHAPTER)), List.of("cover"),
                Set.of(CHAPTER));
        assertThat(arranged).filteredOn(ExtraPage::illustration).extracting(ExtraPage::after)
                .containsExactly(CHAPTER, CHAPTER);
        assertThat(ExtraPage.arrange(List.of(art(a, CHAPTER)), List.of(), Set.of()))
                .extracting(ExtraPage::after).containsExactly("end");
    }
}
