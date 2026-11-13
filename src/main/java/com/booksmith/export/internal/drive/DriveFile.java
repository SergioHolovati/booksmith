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
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** O arquivo de um livro no Drive: o backup seguinte atualiza o mesmo arquivo (o Drive guarda as versões). */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "drive_files", schema = "export")
class DriveFile {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private UUID bookId;

    @Column(nullable = false)
    private String fileId;

    @Column(nullable = false)
    private Instant backedUpAt;
}
