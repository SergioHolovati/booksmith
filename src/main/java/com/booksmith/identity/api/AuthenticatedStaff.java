package com.booksmith.identity.api;

import java.util.UUID;

/** Quem da equipe fez a requisição (token do backoffice; nunca o de um cliente). */
public record AuthenticatedStaff(UUID id, String email, String name, StaffRole role, String sessionId) {
}
