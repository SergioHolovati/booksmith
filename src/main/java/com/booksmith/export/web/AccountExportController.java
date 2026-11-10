package com.booksmith.export.web;

import com.booksmith.common.error.ApiException;
import com.booksmith.export.internal.AccountExportService;
import com.booksmith.identity.api.AuthenticatedUser;
import com.booksmith.identity.api.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** "Baixar tudo" (§28.2): um .zip com livros, universos, conta e pedidos de suporte, escrito direto na resposta. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
class AccountExportController {

    /** Um por vez por pessoa: o arquivo pode ser grande e um segundo toque no botão não deve gerar outro. */
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    private final AccountExportService accountExportService;
    private final CurrentUser currentUser;

    @GetMapping("/me/export")
    ResponseEntity<StreamingResponseBody> export() {
        AuthenticatedUser user = currentUser.get();
        if (!running.add(user.id())) {
            throw ApiException.unprocessable("export_in_progress",
                    "Seu arquivo já está sendo gerado. Espere o download terminar.");
        }
        String author = user.displayName() == null || user.displayName().isBlank() ? user.username() : user.displayName();
        StreamingResponseBody body = out -> {
            try {
                accountExportService.write(user.id(), author, out);
            } finally {
                running.remove(user.id());
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(accountExportService.filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }
}
