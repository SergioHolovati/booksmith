package com.booksmith.assist.internal.illustration;

import com.booksmith.common.error.ApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Monta o pedido ao gerador a partir do formulário: estilo de arte, cor, paleta, detalhe, fundo, enquadramento e
 * expressão (retrato) ou a cena e o formato (capítulo), mais a aparência dos personagens como a ficha descreve.
 * As opções são fixas (o app mostra as mesmas): um valor fora delas é recusado, e o texto livre tem limite.
 */
final class IllustrationPrompt {

    static final int MAX_SCENE = 600;
    static final int MAX_EXTRA = 300;
    static final int MAX_CHARACTERS = 4;

    static final Map<String, String> STYLES = Map.ofEntries(
            Map.entry("lineart", "clean fine line art illustration, precise ink outlines"),
            Map.entry("watercolor", "watercolor painting, soft washes, visible paper texture"),
            Map.entry("ink", "ink drawing with brush and nib, bold blacks, cross-hatching"),
            Map.entry("pencil", "graphite pencil drawing, soft shading, sketchbook feel"),
            Map.entry("anime", "anime and manga illustration style, expressive eyes, cel shading"),
            Map.entry("cartoon", "cartoon illustration, simple shapes, friendly and expressive"),
            Map.entry("comic", "western comic book art, inked outlines, dynamic"),
            Map.entry("childrens", "children's picture book illustration, warm and whimsical"),
            Map.entry("realistic", "realistic illustration, natural lighting, detailed"),
            Map.entry("oil", "oil painting, rich brushstrokes, classical composition"),
            Map.entry("digital", "digital painting, painterly, cinematic lighting"),
            Map.entry("celshading", "cel shaded illustration, flat colors with hard shadows"),
            Map.entry("pixel", "pixel art, limited palette, crisp pixels"),
            Map.entry("woodcut", "woodcut print, linocut texture, high contrast carving marks"));

    static final Map<String, String> PALETTES = Map.of(
            "warm", "warm color palette", "cool", "cool color palette", "neutral", "muted neutral palette",
            "vibrant", "vibrant saturated palette", "pastel", "soft pastel palette");

    static final Map<String, String> DETAILS = Map.of(
            "simple", "simple, minimal detail", "medium", "moderate detail", "rich", "highly detailed");

    static final Map<String, String> BACKGROUNDS = Map.of(
            "scene", "with a fitting background setting", "plain", "on a plain solid background",
            "white", "on a clean white background, isolated subject");

    static final Map<String, String> FRAMINGS = Map.of(
            "face", "head and shoulders portrait", "half", "half body portrait", "full", "full body portrait");

    static final Map<String, String> EXPRESSIONS = Map.ofEntries(
            Map.entry("neutral", "neutral expression"), Map.entry("smile", "gentle smile"),
            Map.entry("laugh", "laughing"), Map.entry("serious", "serious, determined expression"),
            Map.entry("angry", "angry expression"), Map.entry("sad", "sad expression"),
            Map.entry("fear", "frightened expression"), Map.entry("surprise", "surprised expression"),
            Map.entry("smirk", "confident smirk"), Map.entry("thoughtful", "thoughtful, pensive expression"));

    /** Formato da cena → largura × altura (≈1 MP, múltiplos de 32). */
    static final Map<String, int[]> FORMATS = Map.of(
            "page", new int[] {896, 1152},
            "opening", new int[] {1216, 832},
            "vignette", new int[] {1024, 1024});

    private IllustrationPrompt() {
    }

    /** O estilo comum a retrato e cena. */
    record Style(String art, boolean color, String palette, String detail, String background) {
    }

    /** Um personagem que aparece: nome e a aparência da ficha ("Olhos: verdes", "Cabelo: ruivo, cacheado"). */
    record Person(String name, List<String> traits) {
    }

    static String portrait(Style style, Person person, String framing, String expression, String extra) {
        List<String> parts = new ArrayList<>();
        parts.add(require(FRAMINGS, framing, "framing") + " of a single character");
        parts.add(describe(person));
        parts.add(require(EXPRESSIONS, expression, "expression"));
        addStyle(parts, style);
        addExtra(parts, extra);
        parts.add("one character only, no text, no watermark, no lettering");
        return String.join(". ", parts) + ".";
    }

    static String scene(Style style, String scene, List<Person> people, String extra) {
        String text = scene == null ? "" : scene.strip();
        if (text.isEmpty()) {
            throw ApiException.unprocessable("illustration_invalid", "Descreva a cena que a ilustração deve mostrar.");
        }
        if (text.length() > MAX_SCENE) {
            throw ApiException.unprocessable("illustration_invalid",
                    "A descrição da cena passa de %d caracteres.".formatted(MAX_SCENE));
        }
        List<String> parts = new ArrayList<>();
        parts.add("Book illustration of this scene: " + text);
        if (!people.isEmpty()) {
            List<String> who = new ArrayList<>();
            for (Person person : people) {
                who.add(describe(person));
            }
            parts.add("Characters in the scene (keep their appearance consistent with the reference images): "
                    + String.join("; ", who));
        }
        addStyle(parts, style);
        addExtra(parts, extra);
        parts.add("no text, no watermark, no lettering");
        return String.join(". ", parts) + ".";
    }

    static int[] size(String format) {
        int[] size = FORMATS.get(format);
        if (size == null) {
            throw invalid("format");
        }
        return size;
    }

    private static String describe(Person person) {
        if (person.traits().isEmpty()) {
            return person.name();
        }
        return person.name() + " (" + String.join(", ", person.traits()) + ")";
    }

    private static void addStyle(List<String> parts, Style style) {
        parts.add(require(STYLES, style.art(), "art"));
        parts.add(style.color() ? require(PALETTES, style.palette(), "palette")
                : "black and white only, grayscale, no color, suitable for printing in black ink");
        parts.add(require(DETAILS, style.detail(), "detail"));
        parts.add(require(BACKGROUNDS, style.background(), "background"));
    }

    private static void addExtra(List<String> parts, String extra) {
        if (extra == null || extra.isBlank()) {
            return;
        }
        if (extra.strip().length() > MAX_EXTRA) {
            throw ApiException.unprocessable("illustration_invalid",
                    "O pedido extra passa de %d caracteres.".formatted(MAX_EXTRA));
        }
        parts.add("Additional notes: " + extra.strip());
    }

    private static String require(Map<String, String> options, String value, String field) {
        String text = value == null ? null : options.get(value);
        if (text == null) {
            throw invalid(field);
        }
        return text;
    }

    private static ApiException invalid(String field) {
        return ApiException.unprocessable("illustration_invalid", "Opção inválida no formulário (" + field + ").");
    }
}
