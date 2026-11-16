package com.booksmith.assist.internal.illustration;

import com.booksmith.common.error.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Monta o pedido ao gerador. Princípios (o FLUX obedece melhor assim, e o texto do pedido não custa nada; a
 * cobrança é por imagem):
 * <ul>
 *   <li><b>Curto e na ordem de importância:</b> primeiro o que a imagem mostra, depois quem, onde, luz e clima,
 *       composição, estilo e, por último, o que não pode ter. Cada parte é uma frase curta separada por ponto.</li>
 *   <li><b>Só o que se vê:</b> da ficha entram os traços visuais (espécie, idade, pele, olhos, cabelo, corpo, roupa,
 *       marcas), em ordem fixa e com teto de tamanho; personalidade, voz e valores ficam de fora.</li>
 *   <li><b>Opções fechadas:</b> cada escolha do formulário vira uma frase testada; valor desconhecido é recusado.
 *       Texto livre só na cena, no tema da capa e no "mais algum detalhe", com limite.</li>
 *   <li><b>Referências fazem o resto:</b> o retrato do personagem vai como imagem; o texto só reforça.</li>
 * </ul>
 */
final class IllustrationPrompt {

    static final int MAX_SCENE = 600;
    static final int MAX_SUBJECT = 300;
    static final int MAX_EXTRA = 300;
    static final int MAX_CHARACTERS = 4;
    /** Teto de caracteres da descrição de cada personagem (traços compactados). */
    static final int MAX_TRAITS_CHARS = 220;
    static final int MAX_PLACE_CHARS = 180;

    static final Map<String, String> STYLES = Map.ofEntries(
            Map.entry("lineart", "clean fine-line ink illustration"),
            Map.entry("watercolor", "watercolor painting, soft washes, paper texture"),
            Map.entry("ink", "ink and brush drawing, bold blacks, cross-hatching"),
            Map.entry("pencil", "graphite pencil drawing, soft shading"),
            Map.entry("anime", "anime illustration, expressive eyes, cel shading"),
            Map.entry("cartoon", "cartoon illustration, simple shapes"),
            Map.entry("comic", "western comic book art, inked outlines"),
            Map.entry("childrens", "children's picture book illustration, warm and whimsical"),
            Map.entry("realistic", "realistic illustration, natural lighting, detailed"),
            Map.entry("oil", "oil painting, rich brushstrokes"),
            Map.entry("digital", "digital painting, painterly, cinematic lighting"),
            Map.entry("celshading", "cel-shaded illustration, flat colors, hard shadows"),
            Map.entry("pixel", "pixel art, limited palette"),
            Map.entry("woodcut", "woodcut print, linocut texture, high contrast"));

    static final Map<String, String> PALETTES = Map.of(
            "warm", "warm palette", "cool", "cool palette", "neutral", "muted palette",
            "vibrant", "vibrant saturated palette", "pastel", "soft pastel palette");

    static final Map<String, String> DETAILS = Map.of(
            "simple", "minimal detail", "medium", "moderate detail", "rich", "highly detailed");

    static final Map<String, String> BACKGROUNDS = Map.of(
            "scene", "with a fitting background", "plain", "plain solid background",
            "white", "clean white background, isolated subject");

    static final Map<String, String> FRAMINGS = Map.of(
            "face", "head-and-shoulders portrait", "half", "half-body portrait", "full", "full-body portrait");

    static final Map<String, String> EXPRESSIONS = Map.ofEntries(
            Map.entry("neutral", "neutral expression"), Map.entry("smile", "gentle smile"),
            Map.entry("laugh", "laughing"), Map.entry("serious", "serious, determined"),
            Map.entry("angry", "angry"), Map.entry("sad", "sad"),
            Map.entry("fear", "frightened"), Map.entry("surprise", "surprised"),
            Map.entry("smirk", "confident smirk"), Map.entry("thoughtful", "pensive"));

    static final Map<String, String> TIMES = Map.of(
            "dawn", "dawn light", "day", "daylight", "dusk", "golden hour, warm low sun",
            "night", "night, moonlight", "storm", "stormy, dramatic light", "indoor", "soft interior light");

    static final Map<String, String> MOODS = Map.of(
            "calm", "calm, serene mood", "tense", "tense, suspenseful mood", "joyful", "joyful, bright mood",
            "melancholic", "melancholic mood", "mysterious", "mysterious, shadowy mood", "epic", "epic, grand mood",
            "romantic", "romantic, intimate mood", "eerie", "eerie, unsettling mood");

    static final Map<String, String> SHOTS = Map.of(
            "wide", "wide establishing shot", "medium", "medium shot", "close", "close-up",
            "low", "low-angle shot", "high", "high-angle shot");

    /** Formato → largura × altura (≈1 MP, o que o fornecedor cobra como 1; múltiplos de 32). */
    static final Map<String, int[]> FORMATS = Map.of(
            "page", new int[] {896, 1152},
            "opening", new int[] {1216, 832},
            "vignette", new int[] {1024, 1024});
    static final int[] PORTRAIT_SIZE = {896, 1152};
    /** Capa 1:1,6 (1600 × 2560 do editor), abaixo de 1 MP. */
    static final int[] COVER_SIZE = {800, 1280};

    /**
     * Campos da aparência que se veem na imagem, na ordem em que o gerador os aproveita melhor. Personalidade, voz
     * e valores não entram. "Campo da ficha" → "rótulo curto em inglês"; vazio = só o valor.
     */
    static final Map<String, String> VISUAL_FIELDS = visualFields();

    private IllustrationPrompt() {
    }

    record Style(String art, boolean color, String palette, String detail, String background) {
    }

    /** Um personagem: nome e os traços da ficha, campo → valor como a ficha mostra ("Verdes"). */
    record Person(String name, Map<String, String> traits) {
    }

    /** O lugar da cena: nome, tipo ("cidade", "casa"…) e a descrição curta da ficha. */
    record Place(String name, String type, String description) {
    }

    record ScenePlan(String text, String placeHint, List<Person> people, String time, String mood, String shot,
                     String format) {
    }

    static String portrait(Style style, Person person, String framing, String expression, String extra) {
        List<String> parts = new ArrayList<>();
        parts.add(require(FRAMINGS, framing, "framing") + " of " + describe(person) + ", " + require(EXPRESSIONS, expression, "expression"));
        parts.add(styleLine(style));
        addExtra(parts, extra);
        parts.add("single character, no text, no watermark");
        return join(parts);
    }

    static String scene(Style style, String scene, Place place, List<Person> people, String time, String mood,
                        String shot, String extra) {
        String text = required(scene, MAX_SCENE, "Descreva a cena que a ilustração deve mostrar.", "A descrição da cena");
        List<String> parts = new ArrayList<>();
        parts.add("Book illustration: " + text);
        if (!people.isEmpty()) {
            List<String> who = new ArrayList<>();
            for (Person person : people) {
                who.add(describe(person));
            }
            parts.add("Characters: " + String.join("; ", who) + " (match the reference images)");
        }
        if (place != null) {
            parts.add("Setting: " + describe(place));
        }
        addOptional(parts, TIMES, time, "time");
        addOptional(parts, MOODS, mood, "mood");
        addOptional(parts, SHOTS, shot, "shot");
        parts.add(styleLine(style));
        addExtra(parts, extra);
        parts.add("no text, no watermark");
        return join(parts);
    }

    /** A arte da capa, sem letras: o título e o autor entram depois, no editor de capa. */
    static String cover(Style style, String title, boolean fanfic, String subject, List<Person> people, String mood,
                        String extra) {
        String text = required(subject, MAX_SUBJECT, "Diga o que a capa deve mostrar.", "O tema da capa");
        List<String> parts = new ArrayList<>();
        parts.add("Book cover artwork" + (fanfic ? " for a fan fiction" : "") + ": " + text);
        if (!people.isEmpty()) {
            List<String> who = new ArrayList<>();
            for (Person person : people) {
                who.add(describe(person));
            }
            parts.add("Characters: " + String.join("; ", who) + " (match the reference images)");
        }
        addOptional(parts, MOODS, mood, "mood");
        parts.add("vertical composition, main subject in the middle and lower part, calm empty area in the top third for the title");
        parts.add(styleLine(style));
        addExtra(parts, extra);
        parts.add("no text, no letters, no title, no watermark");
        return join(parts);
    }

    static int[] size(String format) {
        int[] size = FORMATS.get(format);
        if (size == null) {
            throw invalid("format");
        }
        return size;
    }

    /** "Lia (human, young adult, light brown skin, green eyes, red curly long hair, slim, worn leather jacket)". */
    static String describe(Person person) {
        StringBuilder traits = new StringBuilder();
        for (Map.Entry<String, String> field : VISUAL_FIELDS.entrySet()) {
            String value = person.traits().get(field.getKey());
            if (value == null || value.isBlank()) {
                continue;
            }
            String piece = (field.getValue().isEmpty() ? "" : field.getValue() + " ") + value.strip().toLowerCase(Locale.ROOT);
            if (traits.length() + piece.length() + 2 > MAX_TRAITS_CHARS) {
                break;
            }
            traits.append(traits.isEmpty() ? "" : ", ").append(piece);
        }
        return traits.isEmpty() ? person.name() : person.name() + " (" + traits + ")";
    }

    static String describe(Place place) {
        StringBuilder text = new StringBuilder(place.name());
        if (place.type() != null && !place.type().isBlank()) {
            text.append(", a ").append(place.type().strip().toLowerCase(Locale.ROOT));
        }
        if (place.description() != null && !place.description().isBlank()) {
            String description = place.description().strip().replaceAll("\\s+", " ");
            if (description.length() > MAX_PLACE_CHARS) {
                int cut = description.lastIndexOf(' ', MAX_PLACE_CHARS);
                description = description.substring(0, cut > 60 ? cut : MAX_PLACE_CHARS) + "…";
            }
            text.append(": ").append(description);
        }
        return text.toString();
    }

    private static String styleLine(Style style) {
        return require(STYLES, style.art(), "art") + ", "
                + (style.color() ? require(PALETTES, style.palette(), "palette")
                        : "black and white, grayscale, for black-ink printing") + ", "
                + require(DETAILS, style.detail(), "detail") + ", "
                + require(BACKGROUNDS, style.background(), "background");
    }

    private static void addOptional(List<String> parts, Map<String, String> options, String value, String field) {
        if (value == null || value.isBlank()) {
            return;
        }
        parts.add(require(options, value, field));
    }

    private static void addExtra(List<String> parts, String extra) {
        if (extra == null || extra.isBlank()) {
            return;
        }
        if (extra.strip().length() > MAX_EXTRA) {
            throw ApiException.unprocessable("illustration_invalid",
                    "O pedido extra passa de %d caracteres.".formatted(MAX_EXTRA));
        }
        parts.add(extra.strip());
    }

    private static String required(String value, int max, String missing, String what) {
        String text = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            throw ApiException.unprocessable("illustration_invalid", missing);
        }
        if (text.length() > max) {
            throw ApiException.unprocessable("illustration_invalid", what + " passa de %d caracteres.".formatted(max));
        }
        return text;
    }

    private static String join(List<String> parts) {
        return String.join(". ", parts) + ".";
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

    private static Map<String, String> visualFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("species", "");
        fields.put("apparentAge", "");
        fields.put("gender", "");
        fields.put("heritage", "");
        fields.put("skinTone", "skin");
        fields.put("eyeColor", "eyes");
        fields.put("eyeShape", "eyes");
        fields.put("hairColor", "hair");
        fields.put("hairTexture", "hair");
        fields.put("hairLength", "hair");
        fields.put("hairStyle", "hair");
        fields.put("facialHair", "");
        fields.put("faceShape", "face");
        fields.put("height", "height");
        fields.put("build", "build");
        fields.put("marks", "");
        fields.put("marksDetail", "");
        fields.put("tattoos", "tattoos");
        fields.put("fantasy", "");
        fields.put("physical", "");
        fields.put("clothingStyle", "wearing");
        fields.put("outfit", "");
        fields.put("colors", "colors");
        fields.put("accessories", "");
        fields.put("glasses", "");
        fields.put("signatureItem", "");
        fields.put("posture", "posture");
        return fields;
    }
}
