package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;

/** Idiomas em que um livro pode ser escrito. Na API e no banco, a tag BCP 47 (ex.: "pt-BR"). */
public enum BookLanguage {
    PT_BR("pt-BR"),
    PT_PT("pt-PT"),
    EN("en"),
    ES("es");

    public static final BookLanguage DEFAULT = PT_BR;

    private final String tag;

    BookLanguage(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }

    /** @throws ApiException 422 se o idioma não for suportado */
    public static BookLanguage fromTag(String tag) {
        return Arrays.stream(values())
                .filter(language -> language.tag.equalsIgnoreCase(tag))
                .findFirst()
                .orElseThrow(() -> ApiException.unprocessable("language_not_supported",
                        "Idioma não suportado. Use pt-BR, pt-PT, en ou es."));
    }

    @Converter(autoApply = true)
    static class JpaConverter implements AttributeConverter<BookLanguage, String> {

        @Override
        public String convertToDatabaseColumn(BookLanguage language) {
            return language == null ? null : language.tag;
        }

        @Override
        public BookLanguage convertToEntityAttribute(String tag) {
            return tag == null ? null : fromTag(tag);
        }
    }
}
