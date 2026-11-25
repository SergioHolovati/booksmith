package com.booksmith.shelf.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.api.UserDirectory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.booksmith.shelf.internal.ShelfPublishing.instant;

/**
 * Fila de denúncias da Estante no backoffice: cada alvo (obra ou comentário) aparece uma vez, com quantas pessoas
 * denunciaram e por quê. Tirar do ar esconde a obra (o autor vê o motivo) ou o comentário; dispensar fecha as
 * denúncias sem mexer em nada.
 */
@Slf4j
@Service
public class ShelfModeration {

    private final JdbcClient jdbc;
    private final UserDirectory users;
    private final Clock clock;

    ShelfModeration(JdbcClient jdbc, UserDirectory users, Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.clock = clock;
    }

    /**
     * Um alvo denunciado. {@code title}: o título da obra (ou da obra do comentário); {@code excerpt}: a sinopse ou o
     * texto do comentário; {@code reasons}: os motivos, sem repetir; {@code details}: o que as pessoas escreveram.
     */
    public record ReportedTarget(String targetType, UUID targetId, String title, String slug, String excerpt,
                                 String authorName, long reports, List<String> reasons, List<String> details,
                                 Instant firstReportedAt) {
    }

    @Transactional(readOnly = true)
    public List<ReportedTarget> open() {
        return jdbc.sql("""
                        select r.target_type, r.target_id, count(*) as reports, array_agg(distinct r.reason) as reasons,
                               array_remove(array_agg(r.details), null) as details, min(r.created_at) as first_at,
                               coalesce(w.title, cw.title) as title, coalesce(w.slug, cw.slug) as slug,
                               coalesce(w.synopsis, m.body) as excerpt, coalesce(w.owner_id, m.user_id) as author
                          from shelf.reports r
                          left join shelf.works w on r.target_type = 'work' and w.id = r.target_id
                          left join shelf.comments m on r.target_type = 'comment' and m.id = r.target_id
                          left join shelf.chapters c on c.id = m.chapter_id
                          left join shelf.works cw on cw.id = c.work_id
                         where r.status = 'open'
                         group by r.target_type, r.target_id, w.title, cw.title, w.slug, cw.slug, w.synopsis, m.body,
                                  w.owner_id, m.user_id
                         order by count(*) desc, min(r.created_at)
                         limit 100
                        """)
                .query((rs, n) -> {
                    UUID author = rs.getObject("author", UUID.class);
                    return new ReportedTarget(rs.getString("target_type"), rs.getObject("target_id", UUID.class),
                            rs.getString("title"), rs.getString("slug"), rs.getString("excerpt"),
                            author == null ? null : users.publicName(author).orElse(null), rs.getLong("reports"),
                            ShelfPublishing.strings(rs, "reasons"), ShelfPublishing.strings(rs, "details"),
                            instant(rs, "first_at"));
                })
                .list();
    }

    /** Tira do ar (obra ou comentário) e fecha as denúncias do alvo. */
    @Transactional
    public void remove(String targetType, UUID targetId, String reason, UUID staffId) {
        ShelfRules.require(targetType, java.util.Set.of("work", "comment"), "target");
        String why = reason == null ? "" : reason.strip();
        if ("work".equals(targetType) && why.isEmpty()) {
            throw ApiException.unprocessable("shelf_reason_required", "Escreva o motivo: o autor vai ver.");
        }
        java.sql.Timestamp now = java.sql.Timestamp.from(Instant.now(clock));
        int changed = "work".equals(targetType)
                ? jdbc.sql("update shelf.works set removed_at = :now, removed_reason = :reason where id = :id and removed_at is null")
                        .param("now", now).param("reason", why).param("id", targetId).update()
                : jdbc.sql("update shelf.comments set hidden_at = :now where id = :id and hidden_at is null")
                        .param("now", now).param("id", targetId).update();
        if (changed == 0) {
            throw ApiException.notFound("shelf_target_not_found", "Já foi tirado do ar ou não existe.");
        }
        close(targetType, targetId, "removed", staffId, now);
        log.info("Moderação da Estante: {} {} tirado do ar", targetType, targetId);
    }

    /** As denúncias não procedem: fecha sem mexer no alvo. */
    @Transactional
    public void dismiss(String targetType, UUID targetId, UUID staffId) {
        ShelfRules.require(targetType, java.util.Set.of("work", "comment"), "target");
        if (close(targetType, targetId, "dismissed", staffId, java.sql.Timestamp.from(Instant.now(clock))) == 0) {
            throw ApiException.notFound("shelf_report_not_found", "Não há denúncias abertas para isso.");
        }
    }

    /** Devolve a obra à Estante (decisão revista). */
    @Transactional
    public void restore(UUID workId) {
        if (jdbc.sql("update shelf.works set removed_at = null, removed_reason = null where id = :id and removed_at is not null")
                .param("id", workId).update() == 0) {
            throw ApiException.notFound("shelf_work_not_removed", "A obra não está fora do ar.");
        }
    }

    private int close(String targetType, UUID targetId, String status, UUID staffId, java.sql.Timestamp now) {
        return jdbc.sql("""
                        update shelf.reports set status = :status, resolved_at = :now, resolved_by = :staff
                         where target_type = :type and target_id = :target and status = 'open'
                        """)
                .param("status", status).param("now", now).param("staff", staffId).param("type", targetType)
                .param("target", targetId)
                .update();
    }
}
