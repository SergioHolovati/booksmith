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

import java.time.Instant;
import java.util.UUID;

/** Relação entre dois personagens ({@link RelationService}). Nomes de coluna explícitos (from/to são palavras SQL). */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "relations", schema = "universe")
public class Relation {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "universe_id", nullable = false)
    private UUID universeId;

    @Column(name = "from_id", nullable = false)
    private UUID fromId;

    @Column(name = "to_id", nullable = false)
    private UUID toId;

    @Column(nullable = false)
    private String kind;

    private String description;

    @Column(name = "since_event_id")
    private UUID sinceEventId;

    @Column(name = "until_event_id")
    private UUID untilEventId;

    @Version
    private long version;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    private Instant deletedAt;
}
