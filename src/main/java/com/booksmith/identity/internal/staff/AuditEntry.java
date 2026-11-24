package com.booksmith.identity.internal.staff;

import java.time.Instant;

/** Uma linha do registro do que a equipe fez. */
public record AuditEntry(String staffEmail, String action, String target, String detail, Instant at) {
}
