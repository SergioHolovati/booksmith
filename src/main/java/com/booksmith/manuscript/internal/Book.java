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

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    private Instant deletedAt;
}
