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
    private final com.booksmith.media.api.FileScanner fileScanner;

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

    /** A estrutura que o arquivo parece ter, para o escritor revisar e aprovar. Não grava nada. */
    @PostMapping(path = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ImportService.StructureView analyze(@PathVariable UUID bookId, @RequestPart("file") MultipartFile file) {
        return importService.analyze(currentUser.id(), bookId, read(file), file.getOriginalFilename());
    }

    /** Importa com a estrutura aprovada ({@code structure}: JSON com as divisões e, se quiser, o sumário). */
    @PostMapping(path = "/structured", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    ImportPlan importStructured(@PathVariable UUID bookId, @RequestPart("file") MultipartFile file,
                                @RequestPart("structure") ImportService.StructureRequest structure) {
        return importService.importStructured(currentUser.id(), bookId, read(file), file.getOriginalFilename(), structure);
    }

    /** Envio de arquivo de texto: até 15 MB (o limite geral é maior por causa do livro pronto em PDF). */
    static final long MAX_TEXT_FILE = 15L * 1024 * 1024;
    /** O livro pronto em PDF: até 100 MB. */
    static final long MAX_PDF = 100L * 1024 * 1024;

    /**
     * O livro pronto em PDF: cada página vira uma página do livro (a página como imagem de fundo). Pode levar
     * alguns minutos num livro grande: as páginas são desenhadas em 300 DPI.
     */
    @PostMapping(path = "/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    ImportService.PdfImport importPdf(@PathVariable UUID bookId, @RequestPart("file") MultipartFile file) {
        if (file.getSize() > MAX_PDF) {
            throw tooLarge(100);
        }
        java.io.File temp = null;
        try {
            temp = java.io.File.createTempFile("booksmith-pdf-", ".pdf");
            file.transferTo(temp);
            if (!isPdf(temp)) {
                throw ApiException.unprocessable("pdf_invalid", "Este arquivo não é um PDF.");
            }
            // Antivírus antes de o PDF ser lido (lido do disco, sem carregar os 100 MB na memória)
            try (var in = new java.io.BufferedInputStream(new java.io.FileInputStream(temp))) {
                fileScanner.requireClean(in);
            }
            return importService.importPdf(currentUser.id(), bookId, temp);
        } catch (IOException e) {
            throw ApiException.unprocessable("import_failed", "Não conseguimos receber o arquivo. Tente de novo.").causedBy(e);
        } finally {
            if (temp != null && !temp.delete()) {
                temp.deleteOnExit();
            }
        }
    }

    /** Assinatura "%PDF-" no começo (pelo conteúdo, não pelo nome). */
    private static boolean isPdf(java.io.File file) throws IOException {
        try (var in = new java.io.FileInputStream(file)) {
            byte[] head = in.readNBytes(5);
            return head.length == 5 && new String(head, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-");
        }
    }

    private static ApiException tooLarge(int mb) {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "file_too_large",
                "O arquivo é grande demais. O limite é de %d MB.".formatted(mb));
    }

    private byte[] read(MultipartFile file) {
        if (file.getSize() > MAX_TEXT_FILE) {
            throw tooLarge(15);
        }
        try {
            byte[] bytes = file.getBytes();
            // Antivírus antes de o arquivo ser lido (Word e LibreOffice são ZIPs com XML)
            fileScanner.requireClean(bytes);
            return bytes;
        } catch (IOException e) {
            throw ApiException.unprocessable("import_failed", "Não conseguimos receber o arquivo. Tente de novo.").causedBy(e);
        }
    }
}
