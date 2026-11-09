package com.booksmith.continuity.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AlertRepository extends JpaRepository<Alert, UUID> {

    List<Alert> findAllByBookId(UUID bookId);

    List<Alert> findAllByBookIdAndStatusInOrderByCreatedAtAsc(UUID bookId, Collection<String> statuses);
}
