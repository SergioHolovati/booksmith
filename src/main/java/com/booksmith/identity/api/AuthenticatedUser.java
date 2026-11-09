package com.booksmith.identity.api;

import java.util.UUID;

/** {@code sessionId} identifica a sessão (aparelho) de onde veio a requisição. */
public record AuthenticatedUser(UUID id, String username, String displayName, String sessionId) {
}
