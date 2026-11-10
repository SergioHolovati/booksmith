package com.booksmith.support.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SupportRequestRepository extends JpaRepository<SupportRequest, UUID> {

    List<SupportRequest> findTop50ByUserIdOrderByCreatedAtDesc(UUID userId);

    List<SupportRequest> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

    long countByUserIdAndCreatedAtAfter(UUID userId, Instant after);
}
