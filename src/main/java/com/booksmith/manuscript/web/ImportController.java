package com.booksmith.manuscript.web;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.api.CurrentUser;
import com.booksmith.manuscript.internal.ImportService;
import com.booksmith.manuscript.internal.ImportService.ImportPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * Importação de .docx, .odt, .md e .txt (§9.3); o formato é reconhecido pelo conteúdo. {@code /docx} é o
 * caminho antigo, mantido para quem já o usa. {@code dryRun=true} só mostra como o arquivo seria dividido; sem ele, os
 * capítulos são criados no fim do livro. {@code mode=single} junta tudo num capítulo só.
 */
@RestController
@RequestMapping("/api/v1/books/{bookId}/import")
@RequiredArgsConstructor
class ImportController {

    private final ImportService importService;
    private final CurrentUser currentUser;

    @PostMapping(path = {"", "/docx"}, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ImportPlan> importFile(@PathVariable UUID bookId,
                                          @RequestPart("file") MultipartFile file,
                                          @RequestParam(defaultValue = "auto") String mode,
                                          @RequestParam(defaultValue = "false") boolean dryRun) {
        boolean single = switch (mode) {
            case "auto" -> false;
            case "single" -> true;
            default -> throw ApiException.badRequest("invalid_mode", "Use mode=auto ou mode=single.");
        };
        byte[] bytes = read(file);
        String name = file.getOriginalFilename();
        if (dryRun) {
            return ResponseEntity.ok(importService.preview(currentUser.id(), bookId, bytes, name, single));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(importService.importFile(currentUser.id(), bookId, bytes, name, single));
    }

    private static byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ApiException.unprocessable("import_failed", "Não conseguimos receber o arquivo. Tente de novo.").causedBy(e);
        }
    }
}
