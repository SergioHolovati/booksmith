package com.booksmith.identity.internal.author;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Perfil público de autor (nome artístico, bio). Opcional: escrever não exige perfil de autor;
 * ele passa a ser necessário para publicar na Estante.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "author_profiles", schema = "identity")
public class AuthorProfile {

    /** Mesmo id do usuário (1:1). */
    @Id
    private UUID userId;

    @Column(nullable = false)
    private String penName;

    private String bio;
    private String avatarUrl;
    private String audience;
    private String language;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> socialLinks = new ArrayList<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> bookGenres = new ArrayList<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> writingStyles = new ArrayList<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> authorReferences = new ArrayList<>();

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
