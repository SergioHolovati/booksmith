package com.booksmith.shelf.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.api.UserDirectory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.booksmith.shelf.internal.ShelfPublishing.instant;
import static com.booksmith.shelf.internal.ShelfPublishing.strings;

/**
 * A leitura: vitrine, ficha da obra, capítulo e página do autor. Sem conta, só o que é público; com conta, também o
 * que é da pessoa (curtiu, segue, está na biblioteca, onde parou). Obra tirada pela moderação some para todos, menos
 * para o autor no painel de publicação.
 */
@Service
public class ShelfReading {

    static final int PAGE_SIZE = 24;

    private static final String CARD_COLUMNS = """
            w.id, w.slug, w.title, w.synopsis, w.cover_url, w.genre, w.fanfic, w.fandom, w.rating, w.warnings, w.tags,
            w.ai_use, w.status, w.owner_id, w.updated_at,
            (select count(*) from shelf.chapters c where c.work_id = w.id) as chapters,
            (select coalesce(sum(c.word_count), 0) from shelf.chapters c where c.work_id = w.id) as words,
            (select count(*) from shelf.likes l join shelf.chapters c on c.id = l.chapter_id where c.work_id = w.id) as likes
            """;

    private final JdbcClient jdbc;
    private final UserDirectory users;

    ShelfReading(JdbcClient jdbc, UserDirectory users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    /** A obra na vitrine e nas listas. */
    public record WorkCard(UUID id, String slug, String title, String synopsis, String coverUrl, String genre,
                           boolean fanfic, String fandom, String rating, List<String> warnings, List<String> tags,
                           String aiUse, String status, UUID authorId, String authorName, long chapters, long words,
                           long likes, Instant updatedAt) {
    }

    /**
     * Filtros da vitrine; todos opcionais. {@code maxRating}: até essa classificação (ex.: "12").
     * {@code excludeTags}: esconde obras com qualquer uma dessas tags. {@code sort}: "recent" (padrão) ou "popular"
     * (mais curtidas nos últimos 7 dias).
     */
    public record Filter(String query, String genre, Boolean fanfic, String tag, String maxRating, String status,
                         int page, List<String> excludeTags, String sort) {

        public Filter(String query, String genre, Boolean fanfic, String tag, String maxRating, String status, int page) {
            this(query, genre, fanfic, tag, maxRating, status, page, List.of(), "recent");
        }
    }

    public record ChapterRef(int number, String title, int words, Instant updatedAt) {
    }

    /** A ficha da obra; {@code reader} vem preenchido só para quem está logado. */
    public record WorkDetail(WorkCard work, List<ChapterRef> chapters, ReaderState reader) {
    }

    /** O que é da pessoa logada nesta obra. {@code lastChapter}: o número do capítulo onde parou (0 = não leu). */
    public record ReaderState(boolean mine, boolean followingAuthor, boolean inLibrary, int lastChapter,
                              double progress) {
    }

    public record ChapterView(String workSlug, String workTitle, UUID authorId, String authorName, String rating,
                              List<String> warnings, int number, int total, String title, String html, int words,
                              Instant updatedAt, long likes, boolean liked, long comments) {
    }

    public record AuthorPage(UUID id, String name, long followers, boolean following, List<WorkCard> works) {
    }

    @Transactional(readOnly = true)
    public List<WorkCard> browse(Filter filter) {
        StringBuilder where = new StringBuilder("w.visibility = 'public' and w.removed_at is null");
        Map<String, Object> params = new HashMap<>();
        if (filter.query() != null && !filter.query().isBlank()) {
            where.append(" and (w.title ilike :q or w.synopsis ilike :q or w.fandom ilike :q)");
            params.put("q", "%" + filter.query().strip().replace("%", "").replace("_", "") + "%");
        }
        if (filter.genre() != null && !filter.genre().isBlank()) {
            where.append(" and w.genre = :genre");
            params.put("genre", filter.genre());
        }
        if (filter.fanfic() != null) {
            where.append(" and w.fanfic = :fanfic");
            params.put("fanfic", filter.fanfic());
        }
        if (filter.tag() != null && !filter.tag().isBlank()) {
            where.append(" and w.tags @> array[:tag]::text[]");
            params.put("tag", filter.tag().strip().toLowerCase(java.util.Locale.ROOT));
        }
        if (filter.maxRating() != null && ShelfRules.RATINGS.contains(filter.maxRating())) {
            where.append(" and w.rating = any(:ratings)");
            params.put("ratings", ShelfRules.RATINGS.subList(0, ShelfRules.RATINGS.indexOf(filter.maxRating()) + 1)
                    .toArray(String[]::new));
        }
        if (filter.status() != null && ShelfRules.STATUSES.contains(filter.status())) {
            where.append(" and w.status = :status");
            params.put("status", filter.status());
        }
        List<String> hidden = filter.excludeTags() == null ? List.of() : filter.excludeTags().stream()
                .filter(t -> t != null && !t.isBlank()).map(t -> t.strip().toLowerCase(java.util.Locale.ROOT))
                .limit(20).toList();
        if (!hidden.isEmpty()) {
            where.append(" and not (w.tags && :hidden::text[])");
            params.put("hidden", hidden.toArray(String[]::new));
        }
        params.put("limit", PAGE_SIZE);
        params.put("offset", Math.max(0, filter.page()) * PAGE_SIZE);
        // Só obras com pelo menos um capítulo publicado
        where.append(" and exists (select 1 from shelf.chapters c where c.work_id = w.id)");
        String order = "popular".equals(filter.sort())
                ? """
                  (select count(*) from shelf.likes l join shelf.chapters c on c.id = l.chapter_id
                    where c.work_id = w.id and l.created_at > now() - interval '7 days') desc, w.updated_at desc"""
                : "w.updated_at desc";
        return cards("select " + CARD_COLUMNS + " from shelf.works w where " + where
                + " order by " + order + " limit :limit offset :offset", params);
    }

    @Transactional(readOnly = true)
    public WorkDetail work(String slug, UUID readerId) {
        WorkCard card = visibleCard(slug);
        List<ChapterRef> chapters = jdbc.sql("""
                        select title, word_count, updated_at from shelf.chapters where work_id = :work order by position
                        """)
                .param("work", card.id())
                .query(new org.springframework.jdbc.core.RowMapper<ChapterRef>() {
                    @Override
                    public ChapterRef mapRow(ResultSet rs, int n) throws SQLException {
                        return new ChapterRef(n + 1, rs.getString("title"), rs.getInt("word_count"),
                                instant(rs, "updated_at"));
                    }
                })
                .list();
        return new WorkDetail(card, chapters, readerId == null ? null : readerState(card, readerId));
    }

    @Transactional(readOnly = true)
    public ChapterView chapter(String slug, int number, UUID readerId) {
        WorkCard card = visibleCard(slug);
        List<UUID> ids = chapterIds(card.id());
        if (number < 1 || number > ids.size()) {
            throw ApiException.notFound("shelf_chapter_not_found", "Capítulo não encontrado.");
        }
        UUID chapterId = ids.get(number - 1);
        return jdbc.sql("""
                        select c.title, c.html, c.word_count, c.updated_at,
                               (select count(*) from shelf.likes l where l.chapter_id = c.id) as likes,
                               exists (select 1 from shelf.likes l where l.chapter_id = c.id and l.user_id = :reader) as liked,
                               (select count(*) from shelf.comments m
                                 where m.chapter_id = c.id and m.deleted_at is null and m.hidden_at is null) as comments
                          from shelf.chapters c where c.id = :id
                        """)
                .param("id", chapterId).param("reader", readerId == null ? new UUID(0, 0) : readerId)
                .query((rs, n) -> new ChapterView(card.slug(), card.title(), card.authorId(), card.authorName(),
                        card.rating(), card.warnings(), number, ids.size(), rs.getString("title"), rs.getString("html"),
                        rs.getInt("word_count"), instant(rs, "updated_at"), rs.getLong("likes"), rs.getBoolean("liked"),
                        rs.getLong("comments")))
                .single();
    }

    /** O id do capítulo publicado (número 1, 2…) de uma obra visível; 404 se não existe. */
    @Transactional(readOnly = true)
    public UUID chapterId(String slug, int number) {
        WorkCard card = visibleCard(slug);
        List<UUID> ids = chapterIds(card.id());
        if (number < 1 || number > ids.size()) {
            throw ApiException.notFound("shelf_chapter_not_found", "Capítulo não encontrado.");
        }
        return ids.get(number - 1);
    }

    /** A obra visível pelo endereço (pública ou não listada, fora do ar não). */
    @Transactional(readOnly = true)
    public WorkCard visibleCard(String slug) {
        Map<String, Object> params = Map.of("slug", slug == null ? "" : slug);
        List<WorkCard> found = cards("select " + CARD_COLUMNS
                + " from shelf.works w where w.slug = :slug and w.removed_at is null", params);
        if (found.isEmpty() || found.getFirst().chapters() == 0) {
            throw ApiException.notFound("shelf_work_not_found", "Obra não encontrada na Estante.");
        }
        return found.getFirst();
    }

    @Transactional(readOnly = true)
    public AuthorPage author(UUID authorId, UUID readerId) {
        List<WorkCard> works = cards("select " + CARD_COLUMNS + " from shelf.works w where w.owner_id = :author"
                + " and w.visibility = 'public' and w.removed_at is null"
                + " and exists (select 1 from shelf.chapters c where c.work_id = w.id) order by w.updated_at desc",
                Map.of("author", authorId));
        String name = users.publicName(authorId)
                .orElseThrow(() -> ApiException.notFound("shelf_author_not_found", "Autor não encontrado."));
        long followers = jdbc.sql("select count(*) from shelf.follows where author_id = :author")
                .param("author", authorId).query(Long.class).single();
        boolean following = readerId != null && jdbc.sql(
                        "select exists (select 1 from shelf.follows where author_id = :author and follower_id = :reader)")
                .param("author", authorId).param("reader", readerId).query(Boolean.class).single();
        return new AuthorPage(authorId, name, followers, following, works);
    }

    /** Biblioteca: as obras guardadas, da lida mais recentemente para a mais antiga. */
    @Transactional(readOnly = true)
    public List<WorkCard> library(UUID userId) {
        return cards("select " + CARD_COLUMNS + " from shelf.library b join shelf.works w on w.id = b.work_id"
                + " where b.user_id = :user and w.removed_at is null"
                + " order by coalesce(b.read_at, b.added_at) desc", Map.of("user", userId));
    }

    /** Novidades de quem a pessoa segue: obras atualizadas, da mais recente para a mais antiga. */
    @Transactional(readOnly = true)
    public List<WorkCard> following(UUID userId) {
        return cards("select " + CARD_COLUMNS + " from shelf.follows f join shelf.works w on w.owner_id = f.author_id"
                + " where f.follower_id = :user and w.visibility = 'public' and w.removed_at is null"
                + " and exists (select 1 from shelf.chapters c where c.work_id = w.id)"
                + " order by w.updated_at desc limit 50", Map.of("user", userId));
    }

    List<UUID> chapterIds(UUID workId) {
        return jdbc.sql("select id from shelf.chapters where work_id = :work order by position")
                .param("work", workId).query(UUID.class).list();
    }

    private ReaderState readerState(WorkCard card, UUID readerId) {
        boolean following = jdbc.sql(
                        "select exists (select 1 from shelf.follows where author_id = :author and follower_id = :reader)")
                .param("author", card.authorId()).param("reader", readerId).query(Boolean.class).single();
        var library = jdbc.sql("select last_chapter_id, progress from shelf.library where user_id = :reader and work_id = :work")
                .param("reader", readerId).param("work", card.id())
                .query((rs, n) -> new Object[]{rs.getObject("last_chapter_id", UUID.class), rs.getDouble("progress")})
                .optional();
        int last = 0;
        double progress = 0;
        if (library.isPresent() && library.get()[0] != null) {
            last = chapterIds(card.id()).indexOf((UUID) library.get()[0]) + 1;
            progress = (double) library.get()[1];
        }
        return new ReaderState(card.authorId().equals(readerId), following, library.isPresent(), last, progress);
    }

    private List<WorkCard> cards(String sql, Map<String, Object> params) {
        Map<UUID, String> names = new HashMap<>();
        return jdbc.sql(sql).params(params)
                .query((rs, n) -> {
                    UUID owner = rs.getObject("owner_id", UUID.class);
                    String author = names.computeIfAbsent(owner, id -> users.publicName(id).orElse("Autor"));
                    return new WorkCard(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("title"),
                            rs.getString("synopsis"), rs.getString("cover_url"), rs.getString("genre"),
                            rs.getBoolean("fanfic"), rs.getString("fandom"), rs.getString("rating"),
                            strings(rs, "warnings"), strings(rs, "tags"), rs.getString("ai_use"), rs.getString("status"),
                            owner, author, rs.getLong("chapters"), rs.getLong("words"), rs.getLong("likes"),
                            instant(rs, "updated_at"));
                })
                .list();
    }
}
