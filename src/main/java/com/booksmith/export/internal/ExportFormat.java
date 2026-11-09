package com.booksmith.export.internal;

import com.booksmith.common.error.ApiException;

import java.util.Arrays;

public enum ExportFormat {
    EPUB("epub", "application/epub+zip"),
    PDF("pdf", "application/pdf"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final String extension;
    private final String contentType;

    ExportFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    /** @throws ApiException 404 se o formato não existe (é parte da URL) */
    public static ExportFormat fromExtension(String extension) {
        return Arrays.stream(values())
                .filter(format -> format.extension.equalsIgnoreCase(extension))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("export_format_not_supported",
                        "Formato de exportação desconhecido. Use epub, pdf ou docx."));
    }
}
