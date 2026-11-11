package com.booksmith.media.api;

import java.util.Optional;
import java.util.UUID;

/** Arquivos PDF do autor (a capa importada), no mesmo armazenamento das imagens. */
public interface PdfStorage {

    /**
     * Guarda um PDF e devolve a URL.
     *
     * @throws com.booksmith.common.error.ApiException 422 {@code pdf_invalid} se não for PDF; {@code pdf_too_large}
     *                                                 se passar do limite
     */
    String store(UUID ownerId, byte[] pdf);

    /** O PDF guardado; vazio se a URL não é deste armazenamento ou o arquivo sumiu. */
    Optional<byte[]> read(String url);

    /** Remove o PDF; falhas são registradas e não propagadas. */
    void delete(String url);
}
