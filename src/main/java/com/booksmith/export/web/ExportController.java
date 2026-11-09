package com.booksmith.export.web;

import com.booksmith.export.internal.ExportFormat;
import com.booksmith.export.internal.ExportService;
import com.booksmith.identity.api.AuthenticatedUser;
import com.booksmith.identity.api.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Baixar o livro em ePub ou PDF de leitura (§13). Gerado na hora; nada fica guardado no servidor. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
class ExportController {

    private final ExportService exportService;
    private final CurrentUser currentUser;

    /** {@code format}: epub ou pdf. */
    @GetMapping("/books/{bookId}/export/{format}")
    ResponseEntity<byte[]> export(@PathVariable UUID bookId, @PathVariable String format) {
        AuthenticatedUser user = currentUser.get();
        String author = user.displayName() == null || user.displayName().isBlank() ? user.username() : user.displayName();
        var file = exportService.export(user.id(), author, bookId, ExportFormat.fromExtension(format));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(file.bytes());
    }
}
