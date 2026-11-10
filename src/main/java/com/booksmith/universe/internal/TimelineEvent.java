package com.booksmith.universe.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Um acontecimento da história, na ordem que o autor definiu. */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "timeline_events", schema = "universe")
public class TimelineEvent {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false)
    private UUID universeId;

    @Column(nullable = false)
    private String title;

    private String whenLabel;

    private String description;

    /** Capítulo onde acontece. Referência lógica: o universo não conhece o manuscrito. */
    private UUID chapterId;

    private UUID placeId;

    @Builder.Default
    @Column(nullable = false, columnDefinition = "uuid[]")
    private UUID[] characterIds = new UUID[0];

    /** Tipo do acontecimento (birth, death, war…); nulo para um evento comum. */
    private String kind;

    /** Data no calendário do mundo ({@link WorldCalendar.WorldDate}); nula quando só há o "quando" em texto. */
    @Column(name = "world_date", columnDefinition = "jsonb")
    @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
    private String worldDate;

    /** Religiões, governos e organizações envolvidos. */
    @Builder.Default
    @Column(name = "lore_ids", nullable = false, columnDefinition = "uuid[]")
    private UUID[] loreIds = new UUID[0];

    @Column(nullable = false)
    private BigDecimal sortKey;

    @Version
    private long version;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    private Instant deletedAt;
}
