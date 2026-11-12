package com.booksmith.export.web;

import com.booksmith.export.internal.ExportFormat;
import com.booksmith.export.internal.PdfPreview;
import com.booksmith.export.internal.PrintCheck;
import com.booksmith.export.internal.PrintCover;
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
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * As ilustrações para impressão com a diagramação do corpo (salva ou não): tamanho na página e DPI. Gráfica
     * pede 300 DPI.
     */
    @PostMapping("/books/{bookId}/export/print-check")
    PrintCheck.Result printCheck(@PathVariable UUID bookId, @RequestBody PreviewRequest request) {
        AuthenticatedUser user = currentUser.get();
        BookLayout layout = new BookLayout(request.trim(), request.fontSize(), request.leading(), request.margins(),
                request.chapterStyle(), request.sceneBreak());
        return exportService.printCheck(user.id(), authorName(user), bookId, layout);
    }

    /** A diagramação (campos soltos, conferidos no {@link BookLayout}) e as páginas pedidas, a partir de 1. */
    record PreviewRequest(String trim, Double fontSize, String leading, String margins, String chapterStyle,
                          String sceneBreak, Integer from, Integer count) {
    }

    private static String authorName(AuthenticatedUser user) {
        return user.displayName() == null || user.displayName().isBlank() ? user.username() : user.displayName();
    }

    /** Medidas da capa aberta para gráfica (contracapa + lombada + frente + sangria). */
    @GetMapping("/books/{bookId}/export/print-cover")
    PrintCover.Spec printCoverSpec(@PathVariable UUID bookId, @RequestParam String profile,
                                   @RequestParam(required = false) Double spineMm,
                                   @RequestParam(required = false) Double bleedMm) {
        AuthenticatedUser user = currentUser.get();
        return exportService.printCoverSpec(user.id(), authorName(user), bookId, profile, spineMm, bleedMm);
    }

    /** PDF da capa aberta, a partir da imagem desenhada no aparelho. */
    @PostMapping("/books/{bookId}/export/print-cover")
    ResponseEntity<byte[]> printCover(@PathVariable UUID bookId, @RequestBody PrintCoverRequest request) {
        AuthenticatedUser user = currentUser.get();
        return attachment(exportService.printCover(user.id(), authorName(user), bookId, request.profile(),
                request.spineMm(), request.bleedMm(), request.image()));
    }

    record PrintCoverRequest(String profile, Double spineMm, Double bleedMm, String image) {
    }

    /** {@code format}: epub ou pdf. */
    @GetMapping("/books/{bookId}/export/{format}")
    ResponseEntity<byte[]> export(@PathVariable UUID bookId, @PathVariable String format) {
        AuthenticatedUser user = currentUser.get();
        return attachment(exportService.export(user.id(), authorName(user), bookId, ExportFormat.fromExtension(format)));
    }

    private static ResponseEntity<byte[]> attachment(ExportService.ExportFile file) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(file.bytes());
    }
}
