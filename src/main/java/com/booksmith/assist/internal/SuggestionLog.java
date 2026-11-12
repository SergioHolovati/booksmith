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

/** Um pedido de sugestões atendido: só os metadados, nunca o texto ({@link AssistService}). */
@Entity
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "suggestions", schema = "assist")
public class SuggestionLog {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String field;

    @Column(nullable = false)
    private String mode;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String model;

    @Column(name = "duration_ms", nullable = false)
    private int durationMs;

    @Column(name = "idea_count", nullable = false)
    private int ideaCount;

    @CreationTimestamp
    private Instant createdAt;
}
