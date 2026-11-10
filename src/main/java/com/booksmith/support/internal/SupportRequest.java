package com.booksmith.support.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
import java.util.Locale;
import java.util.UUID;

/** Pedido de suporte ({@link SupportService}). */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "requests", schema = "support")
public class SupportRequest {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private String message;

    /** Contexto técnico coletado pelo app (só os campos de {@link SupportContext}). */
    @Builder.Default
    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String context = "{}";

    @Column(nullable = false)
    private String priority;

    @Builder.Default
    @Column(nullable = false)
    private String status = "open";

    @Column(name = "helpdesk_ref")
    private String helpdeskRef;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    /** Número curto para o usuário citar ("BS-3F9A2C71"): o fim do id, que é a parte aleatória do UUIDv7. */
    public String protocol() {
        String hex = id.toString().replace("-", "");
        return "BS-" + hex.substring(hex.length() - 8).toUpperCase(Locale.ROOT);
    }
}
