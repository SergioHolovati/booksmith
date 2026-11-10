package com.booksmith.manuscript.internal;

import com.booksmith.manuscript.internal.ProofreadService.Issue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProofreadServiceTest {

    private static Issue spelling(int offset, int length) {
        return new Issue(offset, length, "spelling", "Erro ortográfico", "", List.of(), "HUNSPELL");
    }

    @Test
    void namesFromTheUniverseAndTheBookDictionaryAreNotSpellingErrors() {
        String text = "Kael encontrou Lia no porto de Norvale.";
        Set<String> accepted = Set.of("kael", "norvale");

        assertThat(ProofreadService.isAcceptedSpelling(spelling(0, 4), text, accepted)).isTrue();
        assertThat(ProofreadService.isAcceptedSpelling(spelling(31, 7), text, accepted)).isTrue();
        assertThat(ProofreadService.isAcceptedSpelling(spelling(15, 3), text, accepted)).isFalse();
        // Gramática nunca é descartada pelo dicionário
        var grammar = new Issue(0, 4, "grammar", "", "", List.of(), "X");
        assertThat(ProofreadService.isAcceptedSpelling(grammar, text, accepted)).isFalse();
    }

    @Test
    void errorsGoBackToTheParagraphTheyCameFrom() {
        int[] starts = {0, 12, 30};
        assertThat(ProofreadService.blockAt(starts, 5)).isZero();
        assertThat(ProofreadService.blockAt(starts, 12)).isEqualTo(1);
        assertThat(ProofreadService.blockAt(starts, 31)).isEqualTo(2);
    }

    @Test
    void issueTypesFollowLanguageToolCategories() {
        assertThat(LanguageToolClient.type("misspelling", "TYPOS")).isEqualTo("spelling");
        assertThat(LanguageToolClient.type("grammar", "GRAMMAR")).isEqualTo("grammar");
        assertThat(LanguageToolClient.type("typographical", "PUNCTUATION")).isEqualTo("grammar");
        assertThat(LanguageToolClient.type("style", "REDUNDANCY")).isEqualTo("style");
    }

    @Test
    void cacheKeyIsAHashThatNeverContainsTheText() {
        String key = ProofreadService.key("pt-BR", "Segredo do livro");
        assertThat(key).startsWith("proofread:v1:").doesNotContain("Segredo").hasSize("proofread:v1:".length() + 64);
        assertThat(ProofreadService.key("pt-PT", "Segredo do livro")).isNotEqualTo(key);
    }
}
