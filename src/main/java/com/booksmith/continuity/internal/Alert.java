package com.booksmith.continuity.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Um possível furo de continuidade e a decisão do autor sobre ele. */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "alerts", schema = "continuity")
public class Alert {

    public static final String OPEN = "open";
    public static final String FIXED = "fixed";
    public static final String IGNORED = "ignored";
    public static final String INTENTIONAL = "intentional";
    /** O que o autor pode escolher. "fixed" é decidido pela análise, quando o problema some do texto. */
    public static final Set<String> AUTHOR_STATUSES = Set.of(OPEN, IGNORED, INTENTIONAL);

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false)
    private UUID bookId;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String severity;

    @Column(nullable = false)
    private BigDecimal confidence;

    @Column(nullable = false)
    private String message;

    /** [{chapterId, chapterTitle, blockId, quote, highlight}] */
    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String evidence;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private String fingerprint;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    private Instant resolvedAt;
}
