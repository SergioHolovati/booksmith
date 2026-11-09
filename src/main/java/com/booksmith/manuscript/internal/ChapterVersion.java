package com.booksmith.manuscript.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** Retrato imutável de um capítulo num momento do histórico. */
@Entity
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "chapter_versions", schema = "manuscript")
public class ChapterVersion {

    public enum Reason {
        /** Criada sozinha ao salvar, antes de uma mudança espaçada ou grande. */
        AUTOMATIC,
        /** "Salvar versão" pelo autor, com nome opcional. */
        MANUAL,
        /** Estado do capítulo guardado antes de restaurar uma versão antiga. */
        BEFORE_RESTORE
    }

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false)
    private UUID chapterId;

    @Column(nullable = false)
    private long chapterVersion;

    private String title;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String content;

    private int wordCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Reason reason;

    private String label;

    @Column(nullable = false)
    private Instant createdAt;
}
