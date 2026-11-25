package com.booksmith.shelf.internal;

import com.booksmith.identity.api.UserDirectory;
import com.booksmith.manuscript.api.Manuscripts;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.booksmith.shelf.internal.ShelfPublishing.instant;

/**
 * O que os leitores disseram, para o autor (tela da Estante no livro): os comentários mais recentes da obra inteira,
 * com o capítulo e, quando é de um parágrafo, o começo do trecho. Sem os apagados e os escondidos pela moderação.
 */
@Service
public class ShelfFeedback {

    static final int LIMIT = 50;
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");

    private final JdbcClient jdbc;
    private final Manuscripts manuscripts;
    private final UserDirectory users;

    ShelfFeedback(JdbcClient jdbc, Manuscripts manuscripts, UserDirectory users) {
        this.jdbc = jdbc;
        this.manuscripts = manuscripts;
        this.users = users;
    }

    /**
     * {@code chapterNumber}: o número do capítulo na Estante (1, 2…), para abrir a leitura; {@code quote}: o começo
     * do parágrafo comentado, ou null (comentário do capítulo, ou o parágrafo saiu ao republicar).
     */
    public record ReaderComment(UUID id, int chapterNumber, String chapterTitle, String blockId, String quote,
                                String authorName, String body, Instant createdAt) {
    }

    @Transactional(readOnly = true)
    public List<ReaderComment> recent(UUID ownerId, UUID bookId) {
        manuscripts.requireOwned(ownerId, bookId);
        Map<UUID, String> names = new HashMap<>();
        return jdbc.sql("""
                        select m.id, m.user_id, m.body, m.created_at, m.block_id, c.title, c.html,
                               (select count(*) from shelf.chapters o where o.work_id = c.work_id and o.position <= c.position) as number
                          from shelf.comments m
                          join shelf.chapters c on c.id = m.chapter_id
                          join shelf.works w on w.id = c.work_id
                         where w.book_id = :book and w.owner_id = :owner and m.deleted_at is null and m.hidden_at is null
                         order by m.created_at desc
                         limit :limit
                        """)
                .param("book", bookId).param("owner", ownerId).param("limit", LIMIT)
                .query((rs, n) -> {
                    UUID author = rs.getObject("user_id", UUID.class);
                    String block = rs.getString("block_id");
                    return new ReaderComment(rs.getObject("id", UUID.class), rs.getInt("number"), rs.getString("title"),
                            block, block == null ? null : quote(rs.getString("html"), block),
                            names.computeIfAbsent(author, id -> users.publicName(id).orElse(null)),
                            rs.getString("body"), instant(rs, "created_at"));
                })
                .list();
    }

    /** O começo (até ~100 caracteres) do parágrafo {@code blk-<block>} do HTML publicado; null se ele não existe mais. */
    static String quote(String html, String block) {
        Matcher m = Pattern.compile("<p class=\"[^\"]* blk-" + Pattern.quote(block) + "\">(.*?)</p>", Pattern.DOTALL)
                .matcher(html == null ? "" : html);
        if (!m.find()) {
            return null;
        }
        String text = unescape(TAGS.matcher(m.group(1)).replaceAll("")).replaceAll("\\s+", " ").strip();
        return text.length() <= 100 ? text : text.substring(0, 99).strip() + "…";
    }

    private static String unescape(String text) {
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&#160;", " ").replace("&amp;", "&");
    }
}
