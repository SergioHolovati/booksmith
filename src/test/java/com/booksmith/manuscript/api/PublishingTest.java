package com.booksmith.manuscript.api;

import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublishingTest {

    @Test
    void anIsbnIsCheckedByItsLastDigit() {
        assertThat(Publishing.validIsbn("978-65-00-12345-6")).isTrue();
        assertThat(Publishing.validIsbn("9786500123456")).isTrue();
        assertThat(Publishing.validIsbn("978 65 00 12345 6")).isTrue();
        // um número trocado, o dígito errado, número curto ou de outro prefixo
        assertThat(Publishing.validIsbn("978-65-00-12354-6")).isFalse();
        assertThat(Publishing.validIsbn("978-65-00-12345-5")).isFalse();
        assertThat(Publishing.validIsbn("85-00-12345-6")).isFalse();
        assertThat(Publishing.validIsbn("123-65-00-12345-6")).isFalse();
        assertThat(Publishing.validIsbn("978-65-00-1234a-6")).isFalse();
    }

    @Test
    void cleansWhatTheAuthorTyped() {
        Publishing publishing = new Publishing(" kdp ", "ISBN: 978-65-00-12345-6", "  ",
                java.util.Arrays.asList("fantasia", " Fantasia ", "", null, "romance de época"), Set.of("ficha"));

        assertThat(publishing.destination()).isEqualTo("kdp");
        assertThat(publishing.isbnPrint()).isEqualTo("978-65-00-12345-6");
        assertThat(publishing.isbnEbook()).isNull();
        assertThat(publishing.keywords()).containsExactly("fantasia", "romance de época");
        assertThat(publishing.done()).containsExactly("ficha");
        assertThat(Publishing.digits(publishing.isbnPrint())).isEqualTo("9786500123456");
    }

    @Test
    void refusesWhatDoesNotFit() {
        assertThat(code(() -> new Publishing("editora", null, null, null, null))).isEqualTo("publishing_invalid");
        assertThatThrownBy(() -> new Publishing(null, "978-65-00-12345-5", null, null, null))
                .hasMessageContaining("ISBN do livro impresso não confere");
        assertThatThrownBy(() -> new Publishing(null, "978-65-00-12345-6", "9786500123456", null, null))
                .hasMessageContaining("ISBNs diferentes");
        assertThatThrownBy(() -> new Publishing(null, null, null, List.of("1", "2", "3", "4", "5", "6", "7", "8"), null))
                .hasMessageContaining("até 7 palavras-chave");
        assertThatThrownBy(() -> new Publishing(null, null, null, List.of("x".repeat(51)), null))
                .hasMessageContaining("50 caracteres");
        assertThatThrownBy(() -> new Publishing(null, null, null, null, Set.of("pronto")))
                .hasMessageContaining("Passo desconhecido");
    }

    private static String code(Runnable action) {
        try {
            action.run();
            return null;
        } catch (ApiException e) {
            return e.getCode();
        }
    }
}
