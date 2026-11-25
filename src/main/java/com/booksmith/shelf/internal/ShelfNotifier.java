package com.booksmith.shelf.internal;

import com.booksmith.identity.api.UserDirectory;
import com.booksmith.notification.api.EmailSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Aviso por e-mail de capítulo novo para quem segue o autor. Só obra pública; no máximo um aviso por obra a cada 12
 * horas (quem lança vários capítulos seguidos não enche a caixa de ninguém); quem desligou não recebe. Os e-mails
 * saem depois que a publicação foi gravada, fora da requisição do autor.
 */
@Slf4j
@Service
public class ShelfNotifier {

    static final Duration INTERVAL = Duration.ofHours(12);
    static final int MAX_RECIPIENTS = 1000;

    private final JdbcClient jdbc;
    private final UserDirectory users;
    private final EmailSender email;
    private final Clock clock;
    private final String siteUrl;
    private final Executor executor;

    @org.springframework.beans.factory.annotation.Autowired
    ShelfNotifier(JdbcClient jdbc, UserDirectory users, EmailSender email, Clock clock,
                  @Value("${booksmith.security.frontend-url:http://localhost:4200}") String siteUrl) {
        this(jdbc, users, email, clock, siteUrl, Executors.newVirtualThreadPerTaskExecutor());
    }

    ShelfNotifier(JdbcClient jdbc, UserDirectory users, EmailSender email, Clock clock, String siteUrl,
                  Executor executor) {
        this.jdbc = jdbc;
        this.users = users;
        this.email = email;
        this.clock = clock;
        this.siteUrl = siteUrl.replaceAll("/+$", "");
        this.executor = executor;
    }

    /** O que o e-mail diz: a obra, quem escreveu, o primeiro capítulo novo e quantos são. */
    record NewChapters(UUID workId, String slug, String workTitle, String authorName, int firstNumber,
                       String firstTitle, int count) {
    }

    /**
     * Chamado na publicação (dentro da transação dela), com os capítulos que entraram agora. Decide se avisa (obra
     * pública, 12 horas desde o último aviso) e marca a hora; os e-mails vão depois do commit.
     */
    @Transactional
    public void chaptersAdded(UUID ownerId, NewChapters added) {
        List<UUID> followers = jdbc.sql("""
                        select f.follower_id from shelf.follows f
                          left join shelf.preferences p on p.user_id = f.follower_id
                         where f.author_id = :author and coalesce(p.chapter_emails, true)
                         limit :max
                        """)
                .param("author", ownerId).param("max", MAX_RECIPIENTS)
                .query(UUID.class).list();
        if (followers.isEmpty()) {
            return;
        }
        Instant now = Instant.now(clock);
        int claimed = jdbc.sql("""
                        update shelf.works set last_notified_at = :now
                         where id = :id and visibility = 'public' and removed_at is null
                           and (last_notified_at is null or last_notified_at < :since)
                        """)
                .param("now", java.sql.Timestamp.from(now)).param("id", added.workId())
                .param("since", java.sql.Timestamp.from(now.minus(INTERVAL)))
                .update();
        if (claimed == 0) {
            return;
        }
        Runnable send = () -> send(followers, added);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(send);
                }
            });
        } else {
            executor.execute(send);
        }
    }

    void send(List<UUID> followers, NewChapters added) {
        String title = "Capítulo novo: " + added.workTitle();
        String message = message(added);
        int sent = 0;
        for (UUID follower : followers) {
            try {
                var address = users.email(follower);
                if (address.isPresent()) {
                    email.sendNotice(address.get(), title, message);
                    sent++;
                }
            } catch (RuntimeException e) {
                log.warn("Aviso de capítulo novo não saiu para userId {}: {}", follower, e.getMessage());
            }
        }
        log.info("Aviso de capítulo novo da obra {}: {} e-mails", added.workId(), sent);
    }

    String message(NewChapters added) {
        String what = added.count() == 1
                ? "publicou um capítulo novo"
                : "publicou %d capítulos novos".formatted(added.count());
        return """
                %s %s em "%s": %s.

                Ler: %s/estante/obra/%s/%d

                Você recebe este aviso porque segue %s na Estante. Para não receber mais, desligue em %s/estante/biblioteca."""
                .formatted(added.authorName(), what, added.workTitle(), added.firstTitle(), siteUrl, added.slug(),
                        added.firstNumber(), added.authorName(), siteUrl);
    }

    @Transactional(readOnly = true)
    public boolean chapterEmails(UUID userId) {
        return jdbc.sql("select chapter_emails from shelf.preferences where user_id = :user")
                .param("user", userId).query(Boolean.class).optional().orElse(true);
    }

    @Transactional
    public void setChapterEmails(UUID userId, boolean enabled) {
        jdbc.sql("""
                        insert into shelf.preferences (user_id, chapter_emails, updated_at) values (:user, :enabled, now())
                        on conflict (user_id) do update set chapter_emails = excluded.chapter_emails, updated_at = now()
                        """)
                .param("user", userId).param("enabled", enabled).update();
    }
}
