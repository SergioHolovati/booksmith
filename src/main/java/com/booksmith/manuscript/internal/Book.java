package com.booksmith.manuscript.internal;

import com.booksmith.manuscript.api.Manuscripts;
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
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** Um projeto de escrita (o "projeto" da arquitetura). Pertence ao usuário, não ao perfil de autor. */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "books", schema = "manuscript")
public class Book {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    /** Referência lógica a identity.users (sem FK entre esquemas de módulos). */
    @Column(nullable = false)
    private UUID ownerId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String slug;

    private String synopsis;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookKind kind;

    private String fandom;

    /** Idioma em que o livro é escrito (corretor ortográfico, hifenização, leitores de tela). */
    @Column(nullable = false)
    private BookLanguage language;

    private String coverUrl;

    /** Universo do livro (módulo universe). Referência lógica, sem chave estrangeira entre esquemas. */
    private UUID universeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookStatus status;

    // Páginas antes e depois da história (V21): texto simples, cada linha um parágrafo
    private String credits;

    private String dedication;

    private String epigraph;

    private String epigraphSource;

    private String acknowledgments;

    private String aboutAuthor;

    private String aboutAuthorTitle;

    /** Diagramação do PDF (V22), JSON de {@link com.booksmith.manuscript.api.BookLayout}; "{}" = o padrão. */
    @Builder.Default
    @Column(nullable = false, columnDefinition = "jsonb")
    @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
    private String layout = "{}";

    /** Desenho da capa feita no editor (V23), JSON de {@link CoverDesign}; null = capa não feita no editor. */
    @Column(columnDefinition = "jsonb")
    @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
    private String coverDesign;

    public Manuscripts.BookPages pages() {
        return new Manuscripts.BookPages(credits, dedication, epigraph, epigraphSource, acknowledgments, aboutAuthor,
                aboutAuthorTitle);
    }

    void setPages(Manuscripts.BookPages pages) {
        credits = pages.credits();
        dedication = pages.dedication();
        epigraph = pages.epigraph();
        epigraphSource = pages.epigraphSource();
        acknowledgments = pages.acknowledgments();
        aboutAuthor = pages.aboutAuthor();
        aboutAuthorTitle = pages.aboutAuthorTitle();
    }

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    private Instant deletedAt;
}
