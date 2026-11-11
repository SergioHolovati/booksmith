package com.booksmith.export.web;

import com.booksmith.export.internal.ExportFormat;
import com.booksmith.export.internal.PdfPreview;
import com.booksmith.manuscript.api.BookLayout;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /**
     * Prévia do PDF página a página, com a diagramação do corpo (ainda não salva). Até 4 páginas por pedido.
     */
    @PostMapping("/books/{bookId}/export/preview")
    PdfPreview.Preview preview(@PathVariable UUID bookId, @RequestBody PreviewRequest request) {
        AuthenticatedUser user = currentUser.get();
        BookLayout layout = new BookLayout(request.trim(), request.fontSize(), request.leading(), request.margins(),
                request.chapterStyle(), request.sceneBreak());
        return exportService.preview(user.id(), authorName(user), bookId, layout,
                request.from() == null ? 1 : request.from(), request.count() == null ? 2 : request.count());
    }

    /** A diagramação (campos soltos, conferidos no {@link BookLayout}) e as páginas pedidas, a partir de 1. */
    record PreviewRequest(String trim, Double fontSize, String leading, String margins, String chapterStyle,
                          String sceneBreak, Integer from, Integer count) {
    }

    private static String authorName(AuthenticatedUser user) {
        return user.displayName() == null || user.displayName().isBlank() ? user.username() : user.displayName();
    }

    /** {@code format}: epub ou pdf. */
    @GetMapping("/books/{bookId}/export/{format}")
    ResponseEntity<byte[]> export(@PathVariable UUID bookId, @PathVariable String format) {
        AuthenticatedUser user = currentUser.get();
        var file = exportService.export(user.id(), authorName(user), bookId, ExportFormat.fromExtension(format));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(file.bytes());
    }
}
