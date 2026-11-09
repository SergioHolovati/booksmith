package com.booksmith.manuscript.internal;

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

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "chapters", schema = "manuscript")
public class Chapter {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false)
    private UUID bookId;

    @Column(nullable = false)
    private int position;

    private String title;

    /** Documento ProseMirror serializado. Gravado como jsonb. */
    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String content;

    /** Texto puro derivado do documento (busca, contagem de palavras, IA). */
    @Column(nullable = false)
    private String contentText;

    private int wordCount;

    @Column(nullable = false)
    private String contentHash;

    private int schemaVersion;

    /** Controle otimista: o cliente envia a versão conhecida no If-Match. */
    @Version
    private long version;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    private Instant deletedAt;

    void applyDocument(ManuscriptDocument document) {
        this.content = document.json();
        this.contentText = document.plainText();
        this.wordCount = document.wordCount();
        this.contentHash = document.hash();
        this.schemaVersion = document.schemaVersion();
    }
}
