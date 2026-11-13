package com.booksmith.export.internal.drive;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface DriveFileRepository extends JpaRepository<DriveFile, UUID> {

    Optional<DriveFile> findByUserIdAndBookId(UUID userId, UUID bookId);

    long countByUserId(UUID userId);

    @org.springframework.transaction.annotation.Transactional
    void deleteAllByUserId(UUID userId);
}
