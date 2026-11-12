package com.booksmith.identity.internal.user;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Nome de usuário sugerido a partir do nome da pessoa ("Lia Moreau" → "lia.moreau"), nunca do e-mail (que não
 * deve aparecer para outras pessoas). Se já existe, ganha 4 números no fim.
 */
public final class Usernames {

    static final int MAX_LENGTH = 30;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Usernames() {
    }

    /**
     * @param name  nome que o Google (ou a pessoa) informou; vazio vira "autor"
     * @param taken se um nome de usuário já está em uso
     */
    public static String suggest(String name, Predicate<String> taken) {
        String base = slug(name);
        if (!base.equals("autor") && !taken.test(base)) {
            return base;
        }
        for (int i = 0; i < 20; i++) {
            String candidate = trim(base, MAX_LENGTH - 5) + "." + (1000 + RANDOM.nextInt(9000));
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
        return trim(base, MAX_LENGTH - 9) + "." + (10_000_000 + RANDOM.nextInt(90_000_000));
    }

    /** Sem acento, minúsculo, só letras e números, palavras unidas por ponto. */
    static String slug(String name) {
        if (name == null || name.isBlank()) {
            return "autor";
        }
        String plain = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", ".").replaceAll("^\\.+|\\.+$", "");
        return plain.isEmpty() ? "autor" : trim(plain, MAX_LENGTH);
    }

    private static String trim(String text, int max) {
        String cut = text.length() <= max ? text : text.substring(0, max);
        return cut.replaceAll("\\.+$", "");
    }
}
