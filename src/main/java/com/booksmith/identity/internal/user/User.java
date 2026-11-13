package com.booksmith.identity.internal.user;

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
import java.time.LocalDate;
import java.time.Period;
import java.util.UUID;

@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "users", schema = "identity")
public class User {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private String firstName;
    private String lastName;
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    /** Nulo para contas criadas só com Google. */
    private String passwordHash;

    /** Opcionais no cadastro (minimização de dados, LGPD). Serão exigidos só para receber repasses. */
    private String phone;
    private String document;

    /** Obrigatória no cadastro por e-mail; contas Google completam depois (§19.3). */
    private LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuthProvider provider;

    private boolean googleLinked;
    /** Identidade da conta Google ligada ("sub", não muda); null = sem Google ou ligada antes da V28. */
    private String googleSubject;
    /** E-mail da conta Google ligada (pode ser outro que o do cadastro). */
    private String googleEmail;
    private boolean emailVerified;
    private String emailVerificationCodeHash;
    private Instant emailVerificationExpiresAt;

    private Instant lastLoginAt;

    /** O convite usado para criar a conta (beta fechado); null = conta de antes do beta ou sem convite. */
    private UUID inviteCodeId;

    /** Administrador do Booksmith: cria convites do beta. */
    private boolean admin;

    /** Versão dos termos aceita (ex.: 2026-11-13); diferente da em vigor = precisa aceitar de novo. */
    private String termsVersion;
    private Instant termsAcceptedAt;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public String displayName() {
        String fullName = ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
        return fullName.isEmpty() ? username : fullName;
    }

    public boolean isMinor(LocalDate today) {
        return birthDate != null && Period.between(birthDate, today).getYears() < 18;
    }
}
