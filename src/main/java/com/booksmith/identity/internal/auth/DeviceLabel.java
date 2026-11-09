package com.booksmith.identity.internal.auth;

import java.util.List;
import java.util.Map;

/**
 * Nome legível do aparelho a partir do User-Agent ("Chrome no Android", "Safari no iPhone"). Não pretende
 * ser exato: serve para o usuário reconhecer as próprias sessões. A ordem das listas importa, porque
 * navegadores se anunciam como outros (o Edge diz "Chrome", e o Chrome diz "Safari").
 */
final class DeviceLabel {

    private static final List<Map.Entry<String, String>> BROWSERS = List.of(
            Map.entry("Edg", "Edge"),
            Map.entry("OPR/", "Opera"),
            Map.entry("SamsungBrowser", "Samsung Internet"),
            Map.entry("CriOS", "Chrome"),
            Map.entry("FxiOS", "Firefox"),
            Map.entry("Firefox/", "Firefox"),
            Map.entry("Chrome/", "Chrome"),
            Map.entry("Safari/", "Safari"));

    private static final List<Map.Entry<String, String>> SYSTEMS = List.of(
            Map.entry("iPhone", "iPhone"),
            Map.entry("iPad", "iPad"),
            Map.entry("Android", "Android"),
            Map.entry("CrOS", "Chromebook"),
            Map.entry("Windows", "Windows"),
            Map.entry("Macintosh", "Mac"),
            Map.entry("Linux", "Linux"));

    private DeviceLabel() {
    }

    static String describe(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Aparelho desconhecido";
        }
        String browser = first(BROWSERS, userAgent);
        String system = first(SYSTEMS, userAgent);
        if (browser == null && system == null) {
            return "Aparelho desconhecido";
        }
        if (browser == null) {
            return system;
        }
        return system == null ? browser : browser + " no " + system;
    }

    private static String first(List<Map.Entry<String, String>> candidates, String userAgent) {
        return candidates.stream()
                .filter(c -> userAgent.contains(c.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
