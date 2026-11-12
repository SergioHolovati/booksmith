package com.booksmith.assist.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Uma revisão do capítulo com IA, aberta pelo autor: gasta um uso da cota, e as chamadas das cinco revisões
 * (em partes) usam o id dela, até {@code maxCalls}, só para aquele capítulo e por pouco tempo.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "review_runs", schema = "assist")
public class ReviewRun {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "chapter_id", nullable = false)
    private UUID chapterId;

    @Column(name = "max_calls", nullable = false)
    private int maxCalls;

    @Column(nullable = false)
    private int calls;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
