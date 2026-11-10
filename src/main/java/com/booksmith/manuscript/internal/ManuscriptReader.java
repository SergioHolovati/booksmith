package com.booksmith.manuscript.internal;

import com.booksmith.manuscript.api.Manuscripts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Implementação de {@link Manuscripts}: o texto do livro em blocos, para leitura por outros módulos. */
@Service
@RequiredArgsConstructor
class ManuscriptReader implements Manuscripts {

    private static final java.util.Set<String> TEXT_BLOCKS = java.util.Set.of("paragraph", "heading");

    private final BookService bookService;
    private final BookRepository bookRepository;
    private final JsonMapper jsonMapper;

    @Override
    @Transactional(readOnly = true)
    public BookText bookText(UUID ownerId, UUID bookId) {
        BookService.BookDetail detail = bookService.get(ownerId, bookId);
        List<ChapterText> chapters = new ArrayList<>();
        for (Chapter chapter : detail.chapters()) {
            chapters.add(new ChapterText(chapter.getId(), chapter.getPosition(), chapter.getTitle(),
                    blocks(jsonMapper.readTree(chapter.getContent()))));
        }
        Book book = detail.book();
        return new BookText(book.getId(), book.getTitle(), book.getUniverseId(), chapters);
    }

    @Override
    @Transactional(readOnly = true)
    public BookContent bookContent(UUID ownerId, UUID bookId) {
        BookService.BookDetail detail = bookService.get(ownerId, bookId);
        Book book = detail.book();
        List<ChapterContent> chapters = detail.chapters().stream()
                .map(chapter -> new ChapterContent(chapter.getId(), chapter.getPosition(), chapter.getTitle(),
                        chapter.getContent()))
                .toList();
        return new BookContent(book.getId(), book.getTitle(), book.getSynopsis(), book.getLanguage().tag(),
                book.getKind() == BookKind.FANFIC, book.getFandom(), book.getCoverUrl(), book.getUniverseId(), chapters);
    }

    @Override
    @Transactional(readOnly = true)
    public void requireOwned(UUID ownerId, UUID bookId) {
        bookService.require(ownerId, bookId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> bookIds(UUID ownerId) {
        return bookRepository.findAllByOwnerIdAndDeletedAtIsNull(ownerId,
                        org.springframework.data.domain.Pageable.unpaged()).stream()
                .map(Book::getId)
                .toList();
    }

    /** Parágrafos e títulos em ordem de leitura, inclusive os de dentro de citações e listas. */
    static List<Block> blocks(JsonNode document) {
        List<Block> blocks = new ArrayList<>();
        collect(document, blocks);
        return blocks;
    }

    private static void collect(JsonNode node, List<Block> blocks) {
        JsonNode type = node.get("type");
        if (type != null && TEXT_BLOCKS.contains(type.stringValue())) {
            StringBuilder text = new StringBuilder();
            JsonNode content = node.get("content");
            if (content != null) {
                for (JsonNode inline : content) {
                    JsonNode value = inline.get("text");
                    if (value == null && inline.get("attrs") != null && inline.get("attrs").get("label") != null) {
                        value = inline.get("attrs").get("label"); // menção: o nome do personagem ou lugar
                    }
                    text.append(value != null ? value.stringValue() : " ");
                }
            }
            JsonNode attrs = node.get("attrs");
            String id = attrs != null && attrs.get("id") != null ? attrs.get("id").stringValue() : null;
            if (!text.isEmpty()) {
                blocks.add(new Block(id, text.toString()));
            }
            return;
        }
        JsonNode content = node.get("content");
        if (content != null) {
            for (JsonNode child : content) {
                collect(child, blocks);
            }
        }
    }
}
