package com.booksmith.shelf.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.export.api.ReadingHtml;
import com.booksmith.manuscript.api.Manuscripts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * O lado do autor: publicar o livro na Estante (inteiro ou capítulo a capítulo), atualizar a ficha, republicar e
 * tirar do ar. Cada publicação tira uma cópia do capítulo em HTML (gerado aqui, a partir do documento do editor);
 * mexer no rascunho depois não muda o que os leitores veem até republicar.
 */
@Slf4j
@Service
public class ShelfPublishing {

    private static final String SLUG_CHARS = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final Manuscripts manuscripts;
    private final com.booksmith.identity.api.UserDirectory users;
    private final ShelfNotifier notifier;
    private final ReadingHtml readingHtml;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    ShelfPublishing(JdbcClient jdbc, Manuscripts manuscripts, com.booksmith.identity.api.UserDirectory users,
                    ReadingHtml readingHtml, JsonMapper jsonMapper, Clock clock, ShelfNotifier notifier) {
        this.notifier = notifier;
        this.jdbc = jdbc;
        this.manuscripts = manuscripts;
        this.users = users;
        this.readingHtml = readingHtml;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /**
     * A ficha que o autor escolhe. {@code chapterIds}: os capítulos a publicar ou republicar agora (null = todos);
     * os que já estavam publicados e não vieram continuam como estavam.
     */
    public record Request(String rating, List<String> warnings, List<String> tags, String aiUse, String status,
                          String visibility, List<UUID> chapterIds) {
    }

    /** Como a obra está na Estante, para o painel do autor. */
    public record Publication(UUID workId, String slug, String rating, List<String> warnings, List<String> tags,
                              String aiUse, String status, String visibility, Instant publishedAt, Instant updatedAt,
                              Instant removedAt, String removedReason, List<PublishedChapter> chapters) {
    }

    public record PublishedChapter(UUID chapterId, int position, String title, int words, int edition,
                                   Instant updatedAt) {
    }

    @Transactional(readOnly = true)
    public Optional<Publication> publication(UUID ownerId, UUID bookId) {
        manuscripts.requireOwned(ownerId, bookId);
        return work(bookId).map(this::publication);
    }

    @Transactional
    public Publication publish(UUID ownerId, UUID bookId, Request request) {
        Manuscripts.BookContent book = manuscripts.bookContent(ownerId, bookId);
        if (users.publicName(ownerId).isEmpty()) {
            throw ApiException.unprocessable("shelf_pen_name_required",
                    "Antes de publicar, crie seu nome de autor em Perfil > Perfil de autor. É ele que aparece na Estante.");
        }
        if (book.pictureBook()) {
            throw ApiException.unprocessable("shelf_picture_book",
                    "Livros de páginas ilustradas (infantil ou PDF importado) ainda não podem ir para a Estante.");
        }
        String rating = ShelfRules.rating(request.rating());
        List<String> warnings = ShelfRules.warnings(request.warnings());
        List<String> tags = ShelfRules.tags(request.tags());
        String aiUse = ShelfRules.require(request.aiUse(), ShelfRules.AI_USES, "ai_use");
        String status = ShelfRules.require(request.status(), ShelfRules.STATUSES, "status");
        String visibility = ShelfRules.require(request.visibility(), ShelfRules.VISIBILITIES, "visibility");

        List<Manuscripts.ChapterContent> chosen = chosen(book, request.chapterIds());
        Instant now = Instant.now(clock);
        Optional<WorkRow> existing = work(bookId);
        if (existing.isPresent() && existing.get().removedAt() != null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "shelf_removed",
                    "Esta obra foi tirada da Estante pela moderação: " + existing.get().removedReason());
        }
        UUID workId = existing.map(WorkRow::id).orElseGet(UUID::randomUUID);
        if (existing.isEmpty()) {
            jdbc.sql("""
                            insert into shelf.works (id, book_id, owner_id, slug, title, synopsis, cover_url, genre, fanfic,
                                                     fandom, language, rating, warnings, tags, ai_use, status, visibility,
                                                     published_at, updated_at)
                            values (:id, :book, :owner, :slug, :title, :synopsis, :cover, :genre, :fanfic, :fandom,
                                    :language, :rating, :warnings, :tags, :aiUse, :status, :visibility, :now, :now)
                            """)
                    .param("id", workId).param("book", bookId).param("owner", ownerId).param("slug", newSlug(book.title()))
                    .params(bookFields(book))
                    .param("rating", rating).param("warnings", warnings.toArray(String[]::new))
                    .param("tags", tags.toArray(String[]::new)).param("aiUse", aiUse).param("status", status)
                    .param("visibility", visibility).param("now", java.sql.Timestamp.from(now))
                    .update();
        } else {
            jdbc.sql("""
                            update shelf.works set title = :title, synopsis = :synopsis, cover_url = :cover, genre = :genre,
                                   fanfic = :fanfic, fandom = :fandom, language = :language, rating = :rating,
                                   warnings = :warnings, tags = :tags, ai_use = :aiUse, status = :status,
                                   visibility = :visibility, updated_at = :now
                             where id = :id
                            """)
                    .param("id", workId).params(bookFields(book))
                    .param("rating", rating).param("warnings", warnings.toArray(String[]::new))
                    .param("tags", tags.toArray(String[]::new)).param("aiUse", aiUse).param("status", status)
                    .param("visibility", visibility).param("now", java.sql.Timestamp.from(now))
                    .update();
        }
        Set<UUID> before = new HashSet<>(publishedSources(workId));
        for (Manuscripts.ChapterContent chapter : chosen) {
            publishChapter(workId, chapter, now);
        }
        announce(ownerId, workId, book.title(), before);
        log.info("Obra publicada/atualizada na Estante: workId {} ({} capítulos agora)", workId, chosen.size());
        return publication(work(bookId).orElseThrow());
    }

    /** Os capítulos que entraram agora (não os republicados) viram aviso para quem segue o autor. */
    private void announce(UUID ownerId, UUID workId, String title, Set<UUID> before) {
        List<UUID> sources = publishedSources(workId);
        int first = -1;
        int count = 0;
        for (int i = 0; i < sources.size(); i++) {
            if (!before.contains(sources.get(i))) {
                count++;
                first = first < 0 ? i : first;
            }
        }
        if (count == 0) {
            return;
        }
        WorkRow work = work(bookIdOf(workId)).orElseThrow();
        String chapterTitle = jdbc.sql("select title from shelf.chapters where work_id = :work and source_chapter_id = :source")
                .param("work", workId).param("source", sources.get(first)).query(String.class).single();
        notifier.chaptersAdded(ownerId, new ShelfNotifier.NewChapters(workId, work.slug(), title,
                users.publicName(ownerId).orElse("Autor"), first + 1, chapterTitle, count));
    }

    private List<UUID> publishedSources(UUID workId) {
        return jdbc.sql("select source_chapter_id from shelf.chapters where work_id = :work order by position")
                .param("work", workId).query(UUID.class).list();
    }

    private UUID bookIdOf(UUID workId) {
        return jdbc.sql("select book_id from shelf.works where id = :id").param("id", workId).query(UUID.class).single();
    }

    /** Tira a obra inteira da Estante: comentários, curtidas e o lugar nas bibliotecas vão junto. */
    @Transactional
    public void unpublish(UUID ownerId, UUID bookId) {
        manuscripts.requireOwned(ownerId, bookId);
        WorkRow work = work(bookId).orElseThrow(ShelfPublishing::notPublished);
        if (work.removedAt() != null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "shelf_removed",
                    "A obra foi tirada pela moderação; fale com o suporte.");
        }
        jdbc.sql("delete from shelf.works where id = :id").param("id", work.id()).update();
        log.info("Obra tirada da Estante pelo autor: workId {}", work.id());
    }

    /** Tira um capítulo publicado (o rascunho fica). */
    @Transactional
    public void unpublishChapter(UUID ownerId, UUID bookId, UUID chapterId) {
        manuscripts.requireOwned(ownerId, bookId);
        WorkRow work = work(bookId).orElseThrow(ShelfPublishing::notPublished);
        int removed = jdbc.sql("delete from shelf.chapters where work_id = :work and source_chapter_id = :chapter")
                .param("work", work.id()).param("chapter", chapterId).update();
        if (removed == 0) {
            throw ApiException.notFound("shelf_chapter_not_published", "Este capítulo não está publicado.");
        }
    }

    private List<Manuscripts.ChapterContent> chosen(Manuscripts.BookContent book, List<UUID> ids) {
        Set<UUID> wanted = ids == null ? null : new HashSet<>(ids);
        List<Manuscripts.ChapterContent> chosen = new ArrayList<>();
        for (Manuscripts.ChapterContent chapter : book.chapters()) {
            if (wanted == null || wanted.contains(chapter.chapterId())) {
                chosen.add(chapter);
            }
        }
        if (wanted != null && chosen.size() != wanted.size()) {
            throw ApiException.unprocessable("shelf_unknown_chapter", "Um dos capítulos não é deste livro.");
        }
        if (chosen.isEmpty()) {
            throw ApiException.unprocessable("shelf_no_chapters", "Escolha pelo menos um capítulo para publicar.");
        }
        return chosen;
    }

    private void publishChapter(UUID workId, Manuscripts.ChapterContent chapter, Instant now) {
        String html = readingHtml.chapter(jsonMapper.readTree(chapter.document()));
        int words = ShelfRules.words(html);
        if (words == 0) {
            throw ApiException.unprocessable("shelf_empty_chapter",
                    "O capítulo \"%s\" está vazio. Escreva algo antes de publicar."
                            .formatted(ShelfRules.chapterTitle(chapter.title(), chapter.position() + 1)));
        }
        String title = ShelfRules.chapterTitle(chapter.title(), chapter.position() + 1);
        jdbc.sql("""
                        insert into shelf.chapters (id, work_id, source_chapter_id, position, title, html, word_count,
                                                    edition, published_at, updated_at)
                        values (:id, :work, :source, :position, :title, :html, :words, 1, :now, :now)
                        on conflict (work_id, source_chapter_id) do update
                           set position = excluded.position, title = excluded.title, html = excluded.html,
                               word_count = excluded.word_count, edition = shelf.chapters.edition + 1,
                               updated_at = excluded.updated_at
                        """)
                .param("id", UUID.randomUUID()).param("work", workId).param("source", chapter.chapterId())
                .param("position", chapter.position()).param("title", title).param("html", html)
                .param("words", words).param("now", java.sql.Timestamp.from(now))
                .update();
    }

    private static Map<String, Object> bookFields(Manuscripts.BookContent book) {
        Map<String, Object> fields = new java.util.HashMap<>();
        fields.put("title", book.title());
        fields.put("synopsis", book.synopsis());
        fields.put("cover", book.coverUrl());
        fields.put("genre", book.genre());
        fields.put("fanfic", book.fanfic());
        fields.put("fandom", book.fandom());
        fields.put("language", book.language() == null ? "pt-BR" : book.language());
        return fields;
    }

    /** "o-ultimo-dragao-k3m9tq": o título e 6 letras aleatórias (o endereço não muda se o título mudar). */
    private String newSlug(String title) {
        String base = ShelfRules.slugBase(title);
        for (int attempt = 0; attempt < 5; attempt++) {
            StringBuilder suffix = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                suffix.append(SLUG_CHARS.charAt(RANDOM.nextInt(SLUG_CHARS.length())));
            }
            String slug = base + "-" + suffix;
            boolean taken = jdbc.sql("select exists (select 1 from shelf.works where slug = :slug)")
                    .param("slug", slug).query(Boolean.class).single();
            if (!taken) {
                return slug;
            }
        }
        throw new IllegalStateException("Não foi possível gerar um endereço único para a obra");
    }

    record WorkRow(UUID id, String slug, String rating, List<String> warnings, List<String> tags, String aiUse,
                   String status, String visibility, Instant publishedAt, Instant updatedAt, Instant removedAt,
                   String removedReason) {
    }

    private Optional<WorkRow> work(UUID bookId) {
        return jdbc.sql("""
                        select id, slug, rating, warnings, tags, ai_use, status, visibility, published_at, updated_at,
                               removed_at, removed_reason
                          from shelf.works where book_id = :book
                        """)
                .param("book", bookId)
                .query((rs, n) -> new WorkRow(rs.getObject("id", UUID.class), rs.getString("slug"),
                        rs.getString("rating"), strings(rs, "warnings"), strings(rs, "tags"), rs.getString("ai_use"),
                        rs.getString("status"), rs.getString("visibility"), instant(rs, "published_at"),
                        instant(rs, "updated_at"), instant(rs, "removed_at"), rs.getString("removed_reason")))
                .optional();
    }

    private Publication publication(WorkRow work) {
        List<PublishedChapter> chapters = jdbc.sql("""
                        select source_chapter_id, position, title, word_count, edition, updated_at
                          from shelf.chapters where work_id = :work order by position
                        """)
                .param("work", work.id())
                .query((rs, n) -> new PublishedChapter(rs.getObject("source_chapter_id", UUID.class),
                        rs.getInt("position"), rs.getString("title"), rs.getInt("word_count"), rs.getInt("edition"),
                        instant(rs, "updated_at")))
                .list();
        return new Publication(work.id(), work.slug(), work.rating(), work.warnings(), work.tags(), work.aiUse(),
                work.status(), work.visibility(), work.publishedAt(), work.updatedAt(), work.removedAt(),
                work.removedReason(), chapters);
    }

    static List<String> strings(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static ApiException notPublished() {
        return ApiException.notFound("shelf_not_published", "Este livro não está na Estante.");
    }
}
