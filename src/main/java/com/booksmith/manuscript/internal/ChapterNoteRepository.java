package com.booksmith.manuscript.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChapterNoteRepository extends JpaRepository<ChapterNote, UUID> {

    List<ChapterNote> findAllByChapterIdOrderByCreatedAtAsc(UUID chapterId);

    long countByChapterId(UUID chapterId);
}
