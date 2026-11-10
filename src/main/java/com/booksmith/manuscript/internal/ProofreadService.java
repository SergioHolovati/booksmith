package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.internal.LanguageToolClient.Match;
import com.booksmith.universe.api.Universes;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Corretor próprio (§8.5, etapa 2). O editor manda só os parágrafos que mudaram; cada um é corrigido uma vez e o
 * resultado fica no Redis pelo hash (idioma + texto), por 1 dia. O texto em si não é guardado. Erros de ortografia
 * em nomes do universo ou no dicionário do livro são descartados aqui (o LanguageTool aberto não aceita lista de
 * palavras por chamada).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProofreadService {

    static final int MAX_BLOCKS = 60;
    static final int MAX_BLOCK_CHARS = 5_000;
    static final int MAX_TOTAL_CHARS = 40_000;
    static final int MAX_WORD = 60;
    static final int MAX_DICTIONARY = 2_000;
    static final int CHECKS_PER_MINUTE = 60;
    static final Duration CACHE_TTL = Duration.ofDays(1);
    /** Separa os parágrafos numa chamada só ao LanguageTool. */
    private static final String SEPARATOR = "\n\n";

    private final ChapterService chapterService;
    private final BookService bookService;
    private final Universes universes;
    private final LanguageToolClient languageTool;
    private final StringRedisTemplate redis;
    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    private final Cache<UUID, Bucket> limits = Caffeine.newBuilder().maximumSize(50_000)
            .expireAfterAccess(Duration.ofMinutes(10)).build();

    public record BlockText(String id, String text) {
    }

    public record Issue(int offset, int length, String type, String message, String shortMessage,
                        List<String> replacements, String ruleId) {
    }

    public record BlockIssues(String id, List<Issue> issues) {
    }

    @Transactional(readOnly = true)
    public List<BlockIssues> check(UUID ownerId, UUID chapterId, List<BlockText> blocks) {
        var chapter = chapterService.get(ownerId, chapterId);
        validate(blocks);
        consume(ownerId);
        Book book = chapter.book();
        String language = book.getLanguage() == null ? "pt-BR" : book.getLanguage().tag();

        // Do cache o que já foi corrigido; o resto vai numa chamada só
        Map<String, List<Issue>> byBlock = new LinkedHashMap<>();
        List<BlockText> missing = new ArrayList<>();
        for (BlockText block : blocks) {
            List<Issue> cached = cached(language, block.text());
            if (cached != null) {
                byBlock.put(block.id(), cached);
            } else if (block.text().isBlank()) {
                byBlock.put(block.id(), List.of());
            } else {
                missing.add(block);
            }
        }
        if (!missing.isEmpty()) {
            Map<String, List<Issue>> fresh = checkTogether(missing, language);
            fresh.forEach((id, issues) -> byBlock.put(id, issues));
        }

        Set<String> accepted = acceptedWords(book);
        List<BlockIssues> result = new ArrayList<>();
        for (BlockText block : blocks) {
            List<Issue> issues = byBlock.getOrDefault(block.id(), List.of()).stream()
                    .filter(issue -> !isAcceptedSpelling(issue, block.text(), accepted))
                    .toList();
            result.add(new BlockIssues(block.id(), issues));
        }
        return result;
    }

    // ===== Dicionário do livro =====

    @Transactional(readOnly = true)
    public List<String> dictionary(UUID ownerId, UUID bookId) {
        bookService.require(ownerId, bookId);
        return jdbc.sql("select word from manuscript.book_dictionary where book_id = :book order by lower(word)")
                .param("book", bookId).query(String.class).list();
    }

    @Transactional
    public List<String> addWord(UUID ownerId, UUID bookId, String word) {
        bookService.require(ownerId, bookId);
        String clean = word == null ? "" : word.strip();
        if (clean.isEmpty() || clean.length() > MAX_WORD || clean.contains(" ")) {
            throw ApiException.unprocessable("word_invalid", "Adicione uma palavra só, de até %d letras.".formatted(MAX_WORD));
        }
        Long count = jdbc.sql("select count(*) from manuscript.book_dictionary where book_id = :book")
                .param("book", bookId).query(Long.class).single();
        if (count >= MAX_DICTIONARY) {
            throw ApiException.unprocessable("dictionary_full", "O dicionário do livro chegou a %d palavras.".formatted(MAX_DICTIONARY));
        }
        jdbc.sql("insert into manuscript.book_dictionary (book_id, word) values (:book, :word) on conflict do nothing")
                .param("book", bookId).param("word", clean).update();
        return dictionary(ownerId, bookId);
    }

    @Transactional
    public List<String> removeWord(UUID ownerId, UUID bookId, String word) {
        bookService.require(ownerId, bookId);
        jdbc.sql("delete from manuscript.book_dictionary where book_id = :book and word = :word")
                .param("book", bookId).param("word", word).update();
        return dictionary(ownerId, bookId);
    }

    // ===== Internos =====

    private Map<String, List<Issue>> checkTogether(List<BlockText> blocks, String language) {
        StringBuilder text = new StringBuilder();
        int[] starts = new int[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) {
                text.append(SEPARATOR);
            }
            starts[i] = text.length();
            text.append(blocks.get(i).text());
        }
        Map<String, List<Issue>> result = new HashMap<>();
        blocks.forEach(b -> result.put(b.id(), new ArrayList<>()));
        for (Match match : languageTool.check(text.toString(), language)) {
            int i = blockAt(starts, match.offset());
            int local = match.offset() - starts[i];
            if (local + match.length() > blocks.get(i).text().length()) {
                continue; // atravessa a separação entre parágrafos: não é de nenhum deles
            }
            result.get(blocks.get(i).id()).add(new Issue(local, match.length(), match.type(), match.message(),
                    match.shortMessage(), match.replacements(), match.ruleId()));
        }
        for (BlockText block : blocks) {
            store(language, block.text(), result.get(block.id()));
        }
        return result;
    }

    static int blockAt(int[] starts, int offset) {
        int index = 0;
        for (int i = 0; i < starts.length; i++) {
            if (starts[i] <= offset) {
                index = i;
            }
        }
        return index;
    }

    /** Erro de ortografia num nome do universo ou numa palavra do dicionário do livro não é erro. */
    static boolean isAcceptedSpelling(Issue issue, String text, Set<String> accepted) {
        if (!"spelling".equals(issue.type()) || issue.offset() + issue.length() > text.length()) {
            return false;
        }
        String word = normalize(text.substring(issue.offset(), issue.offset() + issue.length()));
        return accepted.contains(word);
    }

    /** Nomes inteiros e cada palavra deles ("Lia Moreau" aceita "Lia", "Moreau" e "Lia Moreau"). */
    private Set<String> acceptedWords(Book book) {
        Set<String> words = new HashSet<>();
        List<String> sources = new ArrayList<>(universes.vocabulary(book.getUniverseId()));
        sources.addAll(jdbc.sql("select word from manuscript.book_dictionary where book_id = :book")
                .param("book", book.getId()).query(String.class).list());
        for (String name : sources) {
            words.add(normalize(name));
            for (String part : name.split("[\\s\\-'’]+")) {
                if (!part.isBlank()) {
                    words.add(normalize(part));
                }
            }
        }
        return words;
    }

    static String normalize(String word) {
        return Normalizer.normalize(word.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private List<Issue> cached(String language, String text) {
        try {
            String json = redis.opsForValue().get(key(language, text));
            return json == null ? null : jsonMapper.readValue(json, new TypeReference<List<Issue>>() { });
        } catch (RuntimeException e) {
            return null; // sem cache, corrige de novo
        }
    }

    private void store(String language, String text, List<Issue> issues) {
        try {
            redis.opsForValue().set(key(language, text), jsonMapper.writeValueAsString(issues), CACHE_TTL);
        } catch (RuntimeException e) {
            log.debug("Cache do corretor indisponível", e);
        }
    }

    /** Só o hash: o texto do livro não fica no Redis. */
    static String key(String language, String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((language + "\u0000" + text).getBytes(StandardCharsets.UTF_8));
            return "proofread:v1:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void validate(List<BlockText> blocks) {
        if (blocks == null || blocks.size() > MAX_BLOCKS) {
            throw ApiException.unprocessable("proofread_invalid", "Mande até %d parágrafos por vez.".formatted(MAX_BLOCKS));
        }
        int total = 0;
        for (BlockText block : blocks) {
            if (block.id() == null || block.text() == null || block.text().length() > MAX_BLOCK_CHARS) {
                throw ApiException.unprocessable("proofread_invalid", "Parágrafo inválido ou longo demais.");
            }
            total += block.text().length();
        }
        if (total > MAX_TOTAL_CHARS) {
            throw ApiException.unprocessable("proofread_invalid", "Texto longo demais para uma correção só.");
        }
    }

    private void consume(UUID ownerId) {
        Bucket bucket = limits.get(ownerId, id -> Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(CHECKS_PER_MINUTE).refillGreedy(CHECKS_PER_MINUTE, Duration.ofMinutes(1)).build())
                .build());
        if (!bucket.tryConsume(1)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                    "Muitas correções seguidas. Espere um instante.");
        }
    }
}
