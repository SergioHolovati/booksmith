package com.booksmith.identity.internal.staff;

import com.booksmith.identity.api.StaffRole;

import java.time.Instant;
import java.util.UUID;

/**
 * Uma conta da equipe ({@code identity.staff_members}).
 *
 * @param passwordHash null até aceitar o convite
 * @param totpSecret   o segredo do autenticador, selado; null até aceitar o convite
 */
public record StaffMember(UUID id, String email, String name, StaffRole role, String passwordHash, String totpSecret,
                          Long totpLastStep, boolean active, Instant createdAt, Instant activatedAt,
                          Instant lastLoginAt) {

    /** Já aceitou o convite (tem senha e autenticador) e não foi desativado. */
    public boolean canLogIn() {
        return active && passwordHash != null && totpSecret != null;
    }
}
