package com.booksmith.shelf.internal;

import com.booksmith.common.error.ApiException;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** As regras da ficha da obra: classificação, avisos, tags, uso de IA, situação e visibilidade. */
public final class ShelfRules {

    /** Classificação indicativa (Brasil). 18 fica de fora até haver verificação de idade. */
    public static final List<String> RATINGS = List.of("L", "10", "12", "14", "16");
    /** Avisos de conteúdo, no modelo do AO3. */
    public static final Set<String> WARNINGS = Set.of("violence", "death", "self_harm", "abuse", "drugs",
            "sexual_content", "strong_language", "suicide", "eating_disorder");
    public static final Set<String> AI_USES = Set.of("none", "assisted", "generated");
    public static final Set<String> STATUSES = Set.of("ongoing", "complete", "hiatus");
    public static final Set<String> VISIBILITIES = Set.of("public", "unlisted");
    public static final Set<String> REPORT_REASONS = Set.of("plagiarism", "rating", "hate", "sexual", "minor_safety",
            "spam", "other");

    static final int MAX_TAGS = 10;
    static final int MAX_TAG_LENGTH = 30;
    static final int MAX_COMMENT = 2000;
    private static final Pattern NOT_SLUG = Pattern.compile("[^a-z0-9]+");
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");

    private ShelfRules() {
    }

    static String require(String value, Set<String> allowed, String field) {
        if (value == null || !allowed.contains(value)) {
            throw ApiException.unprocessable("shelf_invalid_" + field, "Valor inválido para " + field + ".");
        }
        return value;
    }

    static String rating(String value) {
        if (value == null || !RATINGS.contains(value)) {
            if ("18".equals(value)) {
                throw ApiException.unprocessable("shelf_adult_not_allowed",
                        "Obras 18+ ainda não podem ser publicadas na Estante. A classificação vai até 16 anos.");
            }
            throw ApiException.unprocessable("shelf_invalid_rating", "Escolha a classificação indicativa.");
        }
        return value;
    }

    static List<String> warnings(List<String> values) {
        List<String> clean = new ArrayList<>(new LinkedHashSet<>(values == null ? List.of() : values));
        for (String warning : clean) {
            require(warning, WARNINGS, "warning");
        }
        return clean;
    }

    /** Tags em minúsculas, sem repetir, no máximo 10 de até 30 caracteres. */
    static List<String> tags(List<String> values) {
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        for (String raw : values == null ? List.<String>of() : values) {
            String tag = raw == null ? "" : raw.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            if (tag.startsWith("#")) {
                tag = tag.substring(1).strip();
            }
            if (!tag.isEmpty()) {
                if (tag.length() > MAX_TAG_LENGTH) {
                    throw ApiException.unprocessable("shelf_tag_too_long",
                            "Cada tag pode ter até %d caracteres.".formatted(MAX_TAG_LENGTH));
                }
                clean.add(tag);
            }
        }
        if (clean.size() > MAX_TAGS) {
            throw ApiException.unprocessable("shelf_too_many_tags", "Use até %d tags.".formatted(MAX_TAGS));
        }
        return List.copyOf(clean);
    }

    /** "O Último Dragão!" → "o-ultimo-dragao". */
    static String slugBase(String title) {
        String plain = Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        String slug = NOT_SLUG.matcher(plain).replaceAll("-").replaceAll("(^-+|-+$)", "");
        if (slug.length() > 60) {
            slug = slug.substring(0, 60).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "obra" : slug;
    }

    /** O título que o leitor vê: o do capítulo ou, sem título, "Capítulo N". */
    static String chapterTitle(String title, int number) {
        return title == null || title.isBlank() ? "Capítulo " + number : title.strip();
    }

    /** Palavras do HTML do capítulo (sem as marcas). */
    static int words(String html) {
        String text = TAGS.matcher(html == null ? "" : html).replaceAll(" ").strip();
        return text.isEmpty() ? 0 : text.split("\\s+").length;
    }

    static String comment(String body) {
        String text = body == null ? "" : body.strip();
        if (text.isEmpty()) {
            throw ApiException.unprocessable("shelf_comment_empty", "Escreva o comentário.");
        }
        if (text.length() > MAX_COMMENT) {
            throw ApiException.unprocessable("shelf_comment_too_long",
                    "O comentário pode ter até %d caracteres.".formatted(MAX_COMMENT));
        }
        return text;
    }
}
