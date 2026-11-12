package com.booksmith.assist.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReviewRunRepository extends JpaRepository<ReviewRun, UUID> {

    long countByUserIdAndCreatedAtAfter(UUID userId, Instant after);

    List<ReviewRun> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * Conta mais uma chamada na revisão, se ela é da pessoa, do capítulo, recente e ainda tem chamadas. Numa
     * operação só, para duas chamadas ao mesmo tempo não passarem do limite.
     *
     * @return 1 se pode chamar a IA; 0 se não
     */
    @Modifying
    @Transactional
    @Query("""
            update ReviewRun r set r.calls = r.calls + 1
            where r.id = :id and r.userId = :userId and r.chapterId = :chapterId
              and r.createdAt > :after and r.calls < r.maxCalls""")
    int use(@Param("id") UUID id, @Param("userId") UUID userId, @Param("chapterId") UUID chapterId,
            @Param("after") Instant after);
}
