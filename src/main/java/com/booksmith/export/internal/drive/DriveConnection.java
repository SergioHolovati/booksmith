package com.booksmith.export.internal.drive;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A conta Google onde ficam os backups do usuário. O token de renovação vai criptografado. */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "drive_connections", schema = "export")
class DriveConnection {

    @Id
    private UUID userId;

    private String googleEmail;

    @Column(nullable = false)
    private String refreshToken;

    private String folderId;

    @Column(nullable = false)
    private Instant connectedAt;

    private Instant lastBackupAt;

    private String lastError;
}
