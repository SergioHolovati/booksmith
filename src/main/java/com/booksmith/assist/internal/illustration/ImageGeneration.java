package com.booksmith.assist.internal.illustration;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Uma geração de ilustração (só metadados): para o limite semanal e para medir o custo real. */
@Getter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "image_generations", schema = "assist")
public class ImageGeneration {

    static final String READY = "ready";
    static final String MODERATED = "moderated";
    static final String FAILED = "failed";

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private UUID universeId;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String model;

    @Column(nullable = false)
    private String status;

    private BigDecimal costCredits;

    private UUID imageId;

    @Column(nullable = false)
    private int durationMs;

    @CreationTimestamp
    private Instant createdAt;
}
