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
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** Algo que existe na história: personagem ({@code character}) ou lugar ({@code place}). */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "entities", schema = "universe")
public class UniverseEntity {

    public static final String CHARACTER = "character";
    public static final String PLACE = "place";
    /** Religião, sistema político, idioma ou organização ({@link LoreService}). */
    public static final String LORE = "lore";

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false)
    private UUID universeId;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String name;

    @Builder.Default
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] aliases = new String[0];

    /** Ficha: papel, idade, personalidade, voz... ({@link CharacterSheet}). */
    @Builder.Default
    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String attributes = "{}";

    /** Valores do formulário de aparência ({@link AppearanceForm}). */
    @Builder.Default
    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String appearance = "{}";

    /** Valores dos campos próprios do autor ({@link CustomFields}). */
    @Builder.Default
    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String custom = "{}";

    @Builder.Default
    private int appearanceFormVersion = AppearanceForm.VERSION;

    /**
     * Posição no mapa do universo (0 a 1 da largura e da altura); só para lugares marcados no mapa. Nome da
     * coluna explícito: a convenção do Hibernate transformaria "mapX" em "mapx".
     */
    @Column(name = "map_x")
    private Float mapX;

    @Column(name = "map_y")
    private Float mapY;

    /** Retrato (personagem ou lugar): uma imagem da biblioteca ({@link GalleryService}). */
    @Column(name = "portrait_id")
    private UUID portraitId;

    @Version
    private long version;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    private Instant deletedAt;
}
