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

/** Imagem da biblioteca do universo ({@link GalleryService}). */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "images", schema = "universe")
public class UniverseImage {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "universe_id", nullable = false)
    private UUID universeId;

    @Column(nullable = false)
    private String url;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(nullable = false)
    private long bytes;

    private String caption;

    /** Personagens, lugares e fichas do mundo que aparecem na imagem. */
    @Builder.Default
    @Column(name = "entity_ids", nullable = false, columnDefinition = "uuid[]")
    private UUID[] entityIds = new UUID[0];

    @Version
    private long version;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    private Instant deletedAt;
}
