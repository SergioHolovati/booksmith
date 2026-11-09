package com.booksmith.continuity.internal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Quando o livro foi analisado pela última vez e quanto havia para conferir. */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "analyses", schema = "continuity")
public class Analysis {

    @Id
    private UUID bookId;

    private Instant analyzedAt;

    private int chapters;

    private int entities;
}
