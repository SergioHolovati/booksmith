package com.booksmith.support.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** Uma mensagem da conversa de um pedido de suporte: da pessoa, da equipe ou nota interna da equipe. */
@Entity
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "messages", schema = "support")
public class SupportMessage {

    public static final String USER = "user";
    public static final String STAFF = "staff";

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    /** {@link #USER} ou {@link #STAFF}. */
    @Column(nullable = false)
    private String author;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(nullable = false)
    private String body;

    /** Nota da equipe: a pessoa nunca vê. */
    @Column(nullable = false)
    private boolean internal;

    @CreationTimestamp
    private Instant createdAt;
}
