package com.booksmith.manuscript.internal;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChapterVersionRepository extends JpaRepository<ChapterVersion, UUID> {

    Optional<ChapterVersion> findFirstByChapterIdOrderByCreatedAtDesc(UUID chapterId);

    List<ChapterVersion> findAllByChapterIdOrderByCreatedAtDesc(UUID chapterId, Pageable pageable);

    Optional<ChapterVersion> findByIdAndChapterId(UUID id, UUID chapterId);
}
