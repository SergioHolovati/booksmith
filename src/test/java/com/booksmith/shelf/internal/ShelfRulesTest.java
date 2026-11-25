package com.booksmith.shelf.internal;

import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShelfRulesTest {

    @Test
    void adultRatingIsNotAcceptedYet() {
        assertThat(ShelfRules.rating("14")).isEqualTo("14");
        assertThatThrownBy(() -> ShelfRules.rating("18"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("shelf_adult_not_allowed"));
        assertThatThrownBy(() -> ShelfRules.rating(null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("shelf_invalid_rating"));
    }

    @Test
    void tagsAreLowercaseUniqueAndLimited() {
        assertThat(ShelfRules.tags(List.of(" Slow Burn ", "#slow burn", "Dragões", ""))).containsExactly("slow burn", "dragões");
        assertThatThrownBy(() -> ShelfRules.tags(Collections.nCopies(11, "x").stream().map(t -> t + Math.random()).toList()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("shelf_too_many_tags"));
        assertThatThrownBy(() -> ShelfRules.tags(List.of("a".repeat(31))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("shelf_tag_too_long"));
    }

    @Test
    void warningsComeFromTheList() {
        assertThat(ShelfRules.warnings(List.of("violence", "violence", "death"))).containsExactly("violence", "death");
        assertThatThrownBy(() -> ShelfRules.warnings(List.of("<script>")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void slugIsReadableAndAscii() {
        assertThat(ShelfRules.slugBase("O Último Dragão!")).isEqualTo("o-ultimo-dragao");
        assertThat(ShelfRules.slugBase("???")).isEqualTo("obra");
        assertThat(ShelfRules.slugBase("a".repeat(80))).hasSize(60);
    }

    @Test
    void countsWordsWithoutTheMarkup() {
        assertThat(ShelfRules.words("<p>Era uma <em>vez</em> um dragão.</p>\n<p class=\"scene-break\"></p>")).isEqualTo(5);
        assertThat(ShelfRules.words("")).isZero();
        assertThat(ShelfRules.chapterTitle(" ", 3)).isEqualTo("Capítulo 3");
    }

    @Test
    void commentsAreTrimmedAndLimited() {
        assertThat(ShelfRules.comment("  Amei!  ")).isEqualTo("Amei!");
        assertThatThrownBy(() -> ShelfRules.comment("   "))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("shelf_comment_empty"));
        assertThatThrownBy(() -> ShelfRules.comment("x".repeat(2001)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("shelf_comment_too_long"));
    }
}
