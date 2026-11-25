package com.booksmith.shelf.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.api.UserDirectory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.booksmith.shelf.internal.ShelfPublishing.instant;

/**
 * O que precisa de conta na Estante: seguir autores, biblioteca e ponto de leitura, curtidas, comentários por
 * capítulo e denúncias. Tudo sobre obras visíveis (pública ou não listada, fora do ar não).
 */
@Slf4j
@Service
public class ShelfSocial {

    private final JdbcClient jdbc;
    private final ShelfReading reading;
    private final UserDirectory users;
    private final Clock clock;

    ShelfSocial(JdbcClient jdbc, ShelfReading reading, UserDirectory users, Clock clock) {
        this.jdbc = jdbc;
        this.reading = reading;
        this.users = users;
        this.clock = clock;
    }

    /** {@code authorName}: o nome de autor; null para quem não tem perfil de autor (o app mostra "Leitor"). */
    public record Comment(UUID id, UUID authorId, String authorName, String body, Instant createdAt, boolean mine) {
    }

    @Transactional
    public void follow(UUID userId, UUID authorId) {
        if (userId.equals(authorId)) {
            throw ApiException.unprocessable("shelf_follow_self", "Você não pode seguir a si mesmo.");
        }
        if (users.publicName(authorId).isEmpty()) {
            throw ApiException.notFound("shelf_author_not_found", "Autor não encontrado.");
        }
        jdbc.sql("insert into shelf.follows (follower_id, author_id) values (:user, :author) on conflict do nothing")
                .param("user", userId).param("author", authorId).update();
    }

    @Transactional
    public void unfollow(UUID userId, UUID authorId) {
        jdbc.sql("delete from shelf.follows where follower_id = :user and author_id = :author")
                .param("user", userId).param("author", authorId).update();
    }

    @Transactional
    public void addToLibrary(UUID userId, String slug) {
        UUID work = reading.visibleCard(slug).id();
        jdbc.sql("insert into shelf.library (user_id, work_id) values (:user, :work) on conflict do nothing")
                .param("user", userId).param("work", work).update();
    }

    @Transactional
    public void removeFromLibrary(UUID userId, String slug) {
        UUID work = reading.visibleCard(slug).id();
        jdbc.sql("delete from shelf.library where user_id = :user and work_id = :work")
                .param("user", userId).param("work", work).update();
    }

    /** Onde a pessoa parou (capítulo e quanto dele, de 0 a 1). Guardar o ponto põe a obra na biblioteca. */
    @Transactional
    public void saveProgress(UUID userId, String slug, int number, double progress) {
        UUID work = reading.visibleCard(slug).id();
        UUID chapter = reading.chapterId(slug, number);
        double clamped = Math.clamp(progress, 0, 1);
        jdbc.sql("""
                        insert into shelf.library (user_id, work_id, last_chapter_id, progress, read_at)
                        values (:user, :work, :chapter, :progress, :now)
                        on conflict (user_id, work_id) do update
                           set last_chapter_id = excluded.last_chapter_id, progress = excluded.progress,
                               read_at = excluded.read_at
                        """)
                .param("user", userId).param("work", work).param("chapter", chapter).param("progress", clamped)
                .param("now", java.sql.Timestamp.from(Instant.now(clock)))
                .update();
    }

    @Transactional
    public void like(UUID userId, String slug, int number) {
        UUID chapter = reading.chapterId(slug, number);
        jdbc.sql("insert into shelf.likes (user_id, chapter_id) values (:user, :chapter) on conflict do nothing")
                .param("user", userId).param("chapter", chapter).update();
    }

    @Transactional
    public void unlike(UUID userId, String slug, int number) {
        UUID chapter = reading.chapterId(slug, number);
        jdbc.sql("delete from shelf.likes where user_id = :user and chapter_id = :chapter")
                .param("user", userId).param("chapter", chapter).update();
    }

    /** Comentários do capítulo, do mais antigo para o mais novo; sem os apagados e os escondidos pela moderação. */
    @Transactional(readOnly = true)
    public List<Comment> comments(String slug, int number, UUID readerId) {
        UUID chapter = reading.chapterId(slug, number);
        Map<UUID, String> names = new HashMap<>();
        return jdbc.sql("""
                        select id, user_id, body, created_at from shelf.comments
                         where chapter_id = :chapter and deleted_at is null and hidden_at is null
                         order by created_at limit 500
                        """)
                .param("chapter", chapter)
                .query((rs, n) -> {
                    UUID author = rs.getObject("user_id", UUID.class);
                    return new Comment(rs.getObject("id", UUID.class), author,
                            names.computeIfAbsent(author, id -> users.publicName(id).orElse(null)),
                            rs.getString("body"), instant(rs, "created_at"), author.equals(readerId));
                })
                .list();
    }

    @Transactional
    public Comment comment(UUID userId, String slug, int number, String body) {
        UUID chapter = reading.chapterId(slug, number);
        String text = ShelfRules.comment(body);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        jdbc.sql("insert into shelf.comments (id, chapter_id, user_id, body, created_at) values (:id, :chapter, :user, :body, :now)")
                .param("id", id).param("chapter", chapter).param("user", userId).param("body", text)
                .param("now", java.sql.Timestamp.from(now))
                .update();
        return new Comment(id, userId, users.publicName(userId).orElse(null), text, now, true);
    }

    /** Quem escreveu apaga o próprio comentário; o autor da obra também pode apagar os comentários dela. */
    @Transactional
    public void deleteComment(UUID userId, UUID commentId) {
        int changed = jdbc.sql("""
                        update shelf.comments m set deleted_at = :now
                         where m.id = :id and m.deleted_at is null
                           and (m.user_id = :user or exists (
                                select 1 from shelf.chapters c join shelf.works w on w.id = c.work_id
                                 where c.id = m.chapter_id and w.owner_id = :user))
                        """)
                .param("id", commentId).param("user", userId)
                .param("now", java.sql.Timestamp.from(Instant.now(clock)))
                .update();
        if (changed == 0) {
            throw ApiException.notFound("shelf_comment_not_found", "Comentário não encontrado.");
        }
    }

    /** Denúncia de obra ou comentário: vai para a fila do backoffice. Uma por pessoa e alvo. */
    @Transactional
    public void report(UUID userId, String targetType, UUID targetId, String reason, String details) {
        ShelfRules.require(targetType, java.util.Set.of("work", "comment"), "target");
        ShelfRules.require(reason, ShelfRules.REPORT_REASONS, "reason");
        String clean = details == null ? null : details.strip();
        if (clean != null && clean.length() > 1000) {
            throw ApiException.unprocessable("shelf_report_too_long", "Descreva em até 1000 caracteres.");
        }
        boolean exists = "work".equals(targetType)
                ? jdbc.sql("select exists (select 1 from shelf.works where id = :id and removed_at is null)")
                        .param("id", targetId).query(Boolean.class).single()
                : jdbc.sql("select exists (select 1 from shelf.comments where id = :id and deleted_at is null and hidden_at is null)")
                        .param("id", targetId).query(Boolean.class).single();
        if (!exists) {
            throw ApiException.notFound("shelf_report_target_not_found", "Não encontramos o que você quer denunciar.");
        }
        jdbc.sql("""
                        insert into shelf.reports (id, target_type, target_id, reporter_id, reason, details)
                        values (:id, :type, :target, :user, :reason, :details)
                        on conflict (target_type, target_id, reporter_id) do nothing
                        """)
                .param("id", UUID.randomUUID()).param("type", targetType).param("target", targetId)
                .param("user", userId).param("reason", reason).param("details", clean == null || clean.isEmpty() ? null : clean)
                .update();
        log.info("Denúncia na Estante: {} {} ({})", targetType, targetId, reason);
    }
}
