package com.booksmith.manuscript.internal;

import java.util.UUID;

public record BookStats(UUID bookId, long chapterCount, long wordCount) {

    static BookStats empty(UUID bookId) {
        return new BookStats(bookId, 0, 0);
    }
}
