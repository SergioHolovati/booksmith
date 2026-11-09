package com.booksmith.common.text;

import java.text.Normalizer;

public final class SlugUtils {

    private SlugUtils() {
    }

    public static String toSlug(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")          // remove acentos
                .toLowerCase()
                .trim()
                .replaceAll("[^a-z0-9\\s-]", "")   // remove caracteres especiais
                .replaceAll("\\s+", "-")           // espaço -> hífen
                .replaceAll("-+", "-");            // evita hífens repetidos
    }
}
