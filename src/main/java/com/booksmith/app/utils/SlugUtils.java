package com.booksmith.app.utils;

import java.text.Normalizer;

public class SlugUtils {

    private SlugUtils() {}

    public static String toSlug(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "") // remove acentos
                .toLowerCase()
                .trim()
                .replaceAll("[^a-z0-9\\s-]", "") // remove especiais
                .replaceAll("\\s+", "-") // espaço -> hífen
                .replaceAll("-+", "-"); // evita múltiplos hífens
    }
}