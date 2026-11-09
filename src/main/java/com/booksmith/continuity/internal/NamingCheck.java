package com.booksmith.continuity.internal;

import com.booksmith.continuity.internal.Finding.Evidence;
import com.booksmith.continuity.internal.TextScan.Word;
import com.booksmith.manuscript.api.Manuscripts.Block;
import com.booksmith.manuscript.api.Manuscripts.BookText;
import com.booksmith.manuscript.api.Manuscripts.ChapterText;
import com.booksmith.universe.api.Universes.KnownEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Grafia (§10.4): uma palavra com inicial maiúscula muito parecida com o nome de um personagem ou lugar
 * ("Kaell" × "Kael"), mas que não é nenhum nome cadastrado. Para não confundir com palavras comuns no começo
 * de frase ("Mesa" × "Mesa"), ignora o que também aparece em minúsculas no livro.
 */
final class NamingCheck {

    static final int MIN_LENGTH = 4;
    private static final int MAX_EVIDENCE = 5;

    private NamingCheck() {
    }

    static List<Finding> run(BookText book, List<KnownEntity> entities) {
        // Nomes conhecidos, palavra a palavra ("Lia Souza" → Lia, Souza), com a entidade dona
        Map<String, KnownEntity> known = new LinkedHashMap<>();
        for (KnownEntity entity : entities) {
            List<String> forms = new ArrayList<>(entity.aliases());
            forms.add(entity.name());
            for (String form : forms) {
                for (Word word : TextScan.words(form)) {
                    known.putIfAbsent(word.text(), entity);
                }
            }
        }
        Set<String> knownFolded = new HashSet<>();
        known.keySet().forEach(k -> knownFolded.add(TextScan.fold(k)));

        Set<String> lowercaseWords = new HashSet<>();
        for (ChapterText chapter : book.chapters()) {
            for (Block block : chapter.blocks()) {
                for (Word word : TextScan.words(block.text())) {
                    if (Character.isLowerCase(word.text().charAt(0))) {
                        lowercaseWords.add(TextScan.fold(word.text()));
                    }
                }
            }
        }

        Map<String, Group> groups = new LinkedHashMap<>();
        for (ChapterText chapter : book.chapters()) {
            for (Block block : chapter.blocks()) {
                for (String sentence : TextScan.sentences(block.text())) {
                    for (Word word : TextScan.words(sentence)) {
                        String text = word.text();
                        if (text.length() < MIN_LENGTH || !Character.isUpperCase(text.charAt(0))) {
                            continue;
                        }
                        String folded = TextScan.fold(text);
                        if (knownFolded.contains(folded) || lowercaseWords.contains(folded)) {
                            continue;
                        }
                        Map.Entry<String, KnownEntity> match = closest(text, known);
                        if (match == null) {
                            continue;
                        }
                        String key = folded + "|" + match.getValue().id();
                        groups.computeIfAbsent(key, k -> new Group(text, match.getKey(), match.getValue(), k))
                                .add(new Evidence(chapter.chapterId(), chapter.title(), block.blockId(),
                                        TextScan.quote(sentence, word.start()), text));
                    }
                }
            }
        }
        return groups.values().stream().map(Group::toFinding).toList();
    }

    /** O nome conhecido mais parecido, se estiver perto o bastante: 1 letra até 6 letras, 2 a partir de 7. */
    private static Map.Entry<String, KnownEntity> closest(String word, Map<String, KnownEntity> known) {
        Map.Entry<String, KnownEntity> best = null;
        int bestDistance = Integer.MAX_VALUE;
        String folded = word.toLowerCase(java.util.Locale.ROOT);
        for (var entry : known.entrySet()) {
            String name = entry.getKey();
            if (name.length() < MIN_LENGTH || Character.toLowerCase(name.charAt(0)) != folded.charAt(0)) {
                continue;
            }
            int limit = Math.max(name.length(), word.length()) >= 7 ? 2 : 1;
            int distance = TextScan.distance(folded, name.toLowerCase(java.util.Locale.ROOT));
            if (distance > 0 && distance <= limit && distance < bestDistance) {
                best = entry;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static final class Group {
        private final String surface;
        private final String knownName;
        private final KnownEntity entity;
        private final String key;
        private final List<Evidence> evidence = new ArrayList<>();
        private int count;

        Group(String surface, String knownName, KnownEntity entity, String key) {
            this.surface = surface;
            this.knownName = knownName;
            this.entity = entity;
            this.key = key;
        }

        void add(Evidence item) {
            count++;
            if (evidence.size() < MAX_EVIDENCE) {
                evidence.add(item);
            }
        }

        Finding toFinding() {
            String what = entity.kind().equals("place") ? "o lugar" : "o personagem";
            String times = count == 1 ? "aparece uma vez" : "aparece %d vezes".formatted(count);
            String message = "\"%s\" %s no texto e é quase igual a \"%s\" (%s %s). É o mesmo nome?"
                    .formatted(surface, times, knownName, what, entity.name());
            return new Finding("naming", "low", new BigDecimal("0.70"), message, List.copyOf(evidence),
                    "naming|" + key);
        }
    }
}
