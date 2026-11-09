package com.booksmith.manuscript.internal;

import java.time.Instant;
import java.util.UUID;

public record TrashedChapter(UUID id, String title, int wordCount, UUID bookId, String bookTitle, Instant deletedAt) {
}
