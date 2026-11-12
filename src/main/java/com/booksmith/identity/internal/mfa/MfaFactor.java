package com.booksmith.identity.internal.mfa;

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
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** Um jeito de fazer a segunda etapa: {@code totp} (app autenticador) ou {@code email}. Sem confirmação, não vale. */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "mfa_factors", schema = "identity")
class MfaFactor {

    static final String TOTP = "totp";
    static final String EMAIL = "email";

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String type;

    /** Segredo do TOTP criptografado ({@link com.booksmith.common.crypto.SecretBox}). */
    private String secret;

    /** Último passo de 30 s aceito no TOTP (o mesmo código não vale duas vezes). */
    private Long lastStep;

    private Instant confirmedAt;

    @CreationTimestamp
    private Instant createdAt;

    boolean confirmed() {
        return confirmedAt != null;
    }
}
