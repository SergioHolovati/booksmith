package com.booksmith.continuity.internal;

import com.booksmith.continuity.internal.Finding.Evidence;
import com.booksmith.continuity.internal.TextScan.Word;
import com.booksmith.manuscript.api.Manuscripts.Block;
import com.booksmith.manuscript.api.Manuscripts.BookText;
import com.booksmith.manuscript.api.Manuscripts.ChapterText;
import com.booksmith.universe.api.Universes.KnownEntity;
import com.booksmith.universe.api.Universes.Trait;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Atributo fixo (§10.4): o texto descreve os olhos ou o cabelo de um personagem com uma cor que não combina
 * com a ficha. Prioriza precisão: só atribui a cor a alguém quando a frase deixa claro de quem é ("os olhos
 * azuis de Lia", ou a frase tem um único personagem). "Seus olhos", sem nome na frase, fica para a IA.
 * Cor de pele fica de fora: "morena" no português também descreve o cabelo.
 */
final class AttributeCheck {

    private AttributeCheck() {
    }

    /** Uma característica verificável: o campo da ficha, as palavras que a introduzem e as cores. */
    record Rule(String field, String subjectLabel, Pattern subject, Map<String, Set<String>> colors) {
    }

    /** Palavra do texto (sem acento) → valores do formulário com que ela combina. */
    static final Rule EYES = new Rule("eyeColor", "olhos", Pattern.compile("olhos?|iris|pupilas"), lexicon(Map.of(
            "verde verdes esverdeado esverdeados esverdeada esverdeadas", Set.of("green"),
            "azul azuis azulado azulados azulada azuladas", Set.of("blue"),
            "castanho castanhos castanha castanhas", Set.of("dark_brown", "brown", "hazel"),
            "preto pretos preta pretas negro negros negra negras escuro escuros", Set.of("black", "dark_brown", "brown"),
            "mel ambar", Set.of("hazel", "brown", "golden"),
            "cinza cinzento cinzentos acinzentado acinzentados", Set.of("gray", "blue"),
            "violeta violetas roxo roxos", Set.of("violet"),
            "vermelho vermelhos rubro rubros escarlate escarlates", Set.of("red"),
            "dourado dourados dourada douradas", Set.of("golden", "hazel"))));

    static final Rule HAIR = new Rule("hairColor", "cabelo", Pattern.compile("cabelos?|cachos|madeixas|fios|franja"),
            lexicon(Map.of(
                    "ruivo ruivos ruiva ruivas acobreado acobreados acobreada acobreadas", Set.of("red", "copper"),
                    "loiro loiros loira loiras louro louros loura louras platinado platinados platinada platinadas",
                    Set.of("dark_blond", "blond", "platinum", "light_brown"),
                    "castanho castanhos castanha castanhas", Set.of("dark_brown", "brown", "light_brown"),
                    "preto pretos preta pretas negro negros negra negras", Set.of("black", "dark_brown"),
                    "grisalho grisalhos grisalha grisalhas branco brancos branca brancas prateado prateados",
                    Set.of("gray", "white"),
                    "azul azuis azulado azulados", Set.of("blue"),
                    "rosa rosados rosada rosadas", Set.of("pink"),
                    "verde verdes", Set.of("green"),
                    "roxo roxos roxa roxas lilas", Set.of("purple"))));

    static final List<Rule> RULES = List.of(EYES, HAIR);

    private static final Set<String> NEGATIONS = Set.of("nao", "nem", "sem", "nunca");
    /** Palavras que podem ficar entre "olhos" e a cor sem mudar de quem é a cor ("olhos eram tão verdes"). */
    private static final Set<String> CONNECTORS = Set.of(
            "tao", "muito", "bem", "mais", "bastante", "eram", "era", "sao", "e", "estavam", "estava", "pareciam",
            "parecia", "ficaram", "ficavam", "continuavam", "de", "um", "uma", "intensamente", "profundamente",
            "levemente", "quase", "ainda", "sempre", "seus", "suas", "dela", "dele");
    private static final Set<String> OF = Set.of("de", "da", "do");
    /** Quantas palavras depois de "olhos"/"cabelo" a cor pode aparecer ("olhos tão verdes", "olhos eram verdes"). */
    private static final int COLOR_WINDOW = 4;
    private static final int OWNER_WINDOW = 8;

    static List<Finding> run(BookText book, List<KnownEntity> entities) {
        List<KnownEntity> characters = entities.stream().filter(e -> e.kind().equals("character")).toList();
        Map<KnownEntity, Pattern> mentions = new LinkedHashMap<>();
        characters.forEach(c -> mentions.put(c, mentionPattern(c)));

        // (personagem, campo, cor encontrada) → trechos
        Map<String, Group> groups = new LinkedHashMap<>();
        for (ChapterText chapter : book.chapters()) {
            for (Block block : chapter.blocks()) {
                for (String sentence : TextScan.sentences(block.text())) {
                    scanSentence(sentence, chapter, block, mentions, groups);
                }
            }
        }

        List<Finding> findings = new ArrayList<>();
        for (Group group : groups.values()) {
            findings.add(group.toFinding());
        }
        return findings;
    }

    private static void scanSentence(String sentence, ChapterText chapter, Block block,
                                     Map<KnownEntity, Pattern> mentions, Map<String, Group> groups) {
        List<Word> words = TextScan.words(sentence);
        for (Rule rule : RULES) {
            for (int i = 0; i < words.size(); i++) {
                if (!rule.subject().matcher(TextScan.fold(words.get(i).text())).matches() || negated(words, i)) {
                    continue;
                }
                ColorHit color = color(rule, words, i);
                if (color == null) {
                    continue;
                }
                Owner owner = owner(sentence, words, i, mentions);
                if (owner == null) {
                    continue;
                }
                Trait trait = owner.character().appearance().get(rule.field());
                // Só compara com opções do formulário; texto livre na ficha ("verde-água") não tem como conferir
                if (trait == null || trait.value().equals(trait.label()) || color.values().contains(trait.value())) {
                    continue;
                }
                String key = owner.character().id() + "|" + rule.field() + "|" + color.canonical();
                groups.computeIfAbsent(key, k -> new Group(owner.character(), rule, trait, color, k))
                        .add(new Evidence(chapter.chapterId(), chapter.title(), block.blockId(),
                                TextScan.quote(sentence, words.get(i).start()), color.word()), owner.confidence());
            }
        }
    }

    private static boolean negated(List<Word> words, int index) {
        for (int k = Math.max(0, index - 3); k < index; k++) {
            if (NEGATIONS.contains(TextScan.fold(words.get(k).text()))) {
                return true;
            }
        }
        return false;
    }

    private record ColorHit(String word, Set<String> values, String canonical) {
    }

    private static ColorHit color(Rule rule, List<Word> words, int subject) {
        for (int k = subject + 1; k < Math.min(words.size(), subject + 1 + COLOR_WINDOW); k++) {
            String folded = TextScan.fold(words.get(k).text());
            // "castanho-escuros", "verde-claros": vale a primeira parte
            String head = folded.contains("-") ? folded.substring(0, folded.indexOf('-')) : folded;
            Set<String> values = rule.colors().get(head);
            if (values != null) {
                return new ColorHit(words.get(k).text(), values, values.stream().sorted().findFirst().orElse(head));
            }
            if (!CONNECTORS.contains(folded)) {
                return null; // outra palavra no meio: a cor provavelmente é de outra coisa
            }
        }
        return null;
    }

    private record Owner(KnownEntity character, BigDecimal confidence) {
    }

    /**
     * De quem são os olhos: "… de Lia" logo depois da característica vale mais; senão, o único personagem
     * citado na frase. Frase com dois ou mais personagens e sem "de" fica sem dono (ambígua).
     */
    private static Owner owner(String sentence, List<Word> words, int subject, Map<KnownEntity, Pattern> mentions) {
        for (int k = subject + 1; k < Math.min(words.size() - 1, subject + OWNER_WINDOW); k++) {
            if (OF.contains(words.get(k).lower())) {
                int start = words.get(k + 1).start();
                for (var entry : mentions.entrySet()) {
                    Matcher matcher = entry.getValue().matcher(sentence);
                    if (matcher.find(start) && matcher.start() == start) {
                        return new Owner(entry.getKey(), new BigDecimal("0.90"));
                    }
                }
            }
        }
        KnownEntity only = null;
        for (var entry : mentions.entrySet()) {
            if (entry.getValue().matcher(sentence).find()) {
                if (only != null) {
                    return null;
                }
                only = entry.getKey();
            }
        }
        return only == null ? null : new Owner(only, new BigDecimal("0.80"));
    }

    /** Nome completo, primeiro nome e apelidos, como palavras inteiras com a inicial maiúscula do texto. */
    static Pattern mentionPattern(KnownEntity character) {
        List<String> forms = new ArrayList<>();
        forms.add(character.name());
        String first = character.name().split("\\s+")[0];
        if (first.length() >= 2) {
            forms.add(first);
        }
        forms.addAll(character.aliases());
        String alternatives = forms.stream()
                .filter(f -> !f.isBlank())
                .sorted((a, b) -> b.length() - a.length())
                .map(Pattern::quote)
                .reduce((a, b) -> a + "|" + b)
                .orElse("\\b\\B");
        return Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + alternatives + ")(?![\\p{L}\\p{N}])");
    }

    private static Map<String, Set<String>> lexicon(Map<String, Set<String>> groups) {
        Map<String, Set<String>> lexicon = new LinkedHashMap<>();
        groups.forEach((words, values) -> {
            for (String word : words.split(" ")) {
                lexicon.put(word, values);
            }
        });
        return lexicon;
    }

    /** Todas as ocorrências da mesma cor diferente para o mesmo personagem viram um alerta só. */
    private static final class Group {
        private final KnownEntity character;
        private final Rule rule;
        private final Trait trait;
        private final ColorHit color;
        private final String key;
        private final List<Evidence> evidence = new ArrayList<>();
        private BigDecimal confidence = BigDecimal.ZERO;

        Group(KnownEntity character, Rule rule, Trait trait, ColorHit color, String key) {
            this.character = character;
            this.rule = rule;
            this.trait = trait;
            this.color = color;
            this.key = key;
        }

        void add(Evidence item, BigDecimal itemConfidence) {
            if (evidence.size() < 10) {
                evidence.add(item);
            }
            confidence = confidence.max(itemConfidence);
        }

        Finding toFinding() {
            String times = evidence.size() == 1 ? "" : " em %d trechos".formatted(evidence.size());
            String message = "Na ficha, %s de %s: %s. O texto diz \"%s\"%s.".formatted(
                    rule.subjectLabel(), character.name(), trait.label().toLowerCase(java.util.Locale.ROOT),
                    color.word(), times);
            return new Finding("attribute", "medium", confidence, message, List.copyOf(evidence),
                    "attribute|" + key);
        }
    }
}
