package com.booksmith.media.api;

import java.io.IOException;
import java.io.InputStream;

/**
 * Antivírus dos arquivos enviados (imagens, PDFs, Word, LibreOffice, texto), antes de qualquer outra leitura.
 */
public interface FileScanner {

    /**
     * @throws com.booksmith.common.error.ApiException 422 {@code file_infected} se o antivírus achou algo. Se o
     *                                                 antivírus não está disponível, o arquivo passa (registrado no log)
     */
    void requireClean(byte[] content);

    /** Idem, lendo de um arquivo grande sem carregar tudo na memória. */
    void requireClean(InputStream content) throws IOException;
}
