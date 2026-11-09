package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookLanguageTest {

    @Test
    void acceptsSupportedTagsIgnoringCase() {
        assertThat(BookLanguage.fromTag("pt-BR")).isEqualTo(BookLanguage.PT_BR);
        assertThat(BookLanguage.fromTag("pt-br")).isEqualTo(BookLanguage.PT_BR);
        assertThat(BookLanguage.fromTag("en")).isEqualTo(BookLanguage.EN);
        assertThat(BookLanguage.DEFAULT.tag()).isEqualTo("pt-BR");
    }

    @Test
    void rejectsUnsupportedTag() {
        assertThatThrownBy(() -> BookLanguage.fromTag("klingon"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("language_not_supported"));
    }
}
