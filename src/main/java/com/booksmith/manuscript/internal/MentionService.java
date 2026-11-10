package com.booksmith.manuscript.internal;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Onde um personagem, lugar ou ficha do mundo é mencionado com "@" (§4.1 do produto): os capítulos da pessoa
 * que têm a menção e quantas vezes. Busca direto no documento (jsonpath), sem índice próprio por enquanto.
 */
@Service
@RequiredArgsConstructor
public class MentionService {

    private final JdbcClient jdbc;

    /**
     * Precisa ser {@code strict}: no modo padrão (lax), {@code $.**} desembrulha as listas e visita o mesmo nó duas
     * vezes, contando cada menção em dobro.
     */
    @Transactional(readOnly = true)
    public List<MentionedIn> chaptersMentioning(UUID ownerId, UUID entityId) {
        return jdbc.sql("""
                        select b.id as book_id, b.title as book_title, c.id as chapter_id, c.title as chapter_title,
                               c.position,
                               jsonb_array_length(jsonb_path_query_array(c.content,
                                   'strict $.** ? (@.type == "mention" && @.attrs.id == $id)', jsonb_build_object('id', :entity))) as hits
                        from manuscript.chapters c
                        join manuscript.books b on b.id = c.book_id
                        where b.owner_id = :owner and b.deleted_at is null and c.deleted_at is null
                          and jsonb_path_exists(c.content, 'strict $.** ? (@.type == "mention" && @.attrs.id == $id)',
                                                jsonb_build_object('id', :entity))
                        order by b.updated_at desc, c.position
                        """)
                .param("owner", ownerId)
                .param("entity", entityId.toString())
                .query((rs, n) -> new MentionedIn(rs.getObject("book_id", UUID.class), rs.getString("book_title"),
                        rs.getObject("chapter_id", UUID.class), rs.getString("chapter_title"), rs.getInt("position"),
                        rs.getInt("hits")))
                .list();
    }

    /** {@code count}: quantas vezes o capítulo menciona. */
    public record MentionedIn(UUID bookId, String bookTitle, UUID chapterId, String chapterTitle, int position,
                              int count) {
    }
}
