package com.booksmith.support.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SupportMessageRepository extends JpaRepository<SupportMessage, UUID> {

    List<SupportMessage> findAllByRequestIdOrderByCreatedAtAsc(UUID requestId);

    /** A conversa como a pessoa vê (sem as notas internas). */
    List<SupportMessage> findAllByRequestIdAndInternalFalseOrderByCreatedAtAsc(UUID requestId);
}
