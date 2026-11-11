package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoverDesignTest {

    @Test
    void emptyChoicesGetDefaultsAndTextIsCleaned() {
        var design = new CoverDesign(null, null, null, null, "  A Conversão\n", " ", "Lia", null, null, "#FFAA00", null);
        assertThat(design.template()).isEqualTo("classic");
        assertThat(design.backgroundColor()).isEqualTo("#1f2a44");
        assertThat(design.shade()).isZero();
        assertThat(design.title()).isEqualTo("A Conversão");
        assertThat(design.subtitle()).isNull();
        assertThat(design.font()).isEqualTo("serif");
        assertThat(design.titleSize()).isEqualTo("m");
        assertThat(design.textColor()).isEqualTo("#ffaa00");
    }

    @Test
    void badChoicesAreRejected() {
        assertInvalid(() -> new CoverDesign("poster", null, null, null, "T", null, null, null, null, null, null));
        assertInvalid(() -> new CoverDesign(null, "red", null, null, "T", null, null, null, null, null, null));
        assertInvalid(() -> new CoverDesign(null, null, null, 90, "T", null, null, null, null, null, null));
        assertInvalid(() -> new CoverDesign(null, null, null, null, " ", null, null, null, null, null, null));
        assertInvalid(() -> new CoverDesign(null, null, null, null, "x".repeat(201), null, null, null, null, null, null));
        assertInvalid(() -> new CoverDesign(null, null, null, null, "T", null, null, "comic", null, null, null));
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("cover_design_invalid"));
    }
}
