package com.booksmith.media.api;

import java.util.UUID;

public interface ImageStorage {

    /**
     * Armazena uma imagem recebida como data URL ({@code data:image/png;base64,...}).
     * O formato é validado pelos bytes do arquivo, não pelo prefixo declarado.
     *
     * @throws com.booksmith.common.error.ApiException 422 se o arquivo não for PNG, JPEG ou WebP, ou passar do limite
     */
    StoredImage store(ImageCategory category, UUID ownerId, String dataUrl);

    /**
     * Lê de volta uma imagem armazenada (ex.: a capa, para embutir no ePub). Vazio se a URL não é deste
     * armazenamento, o arquivo sumiu ou a leitura falhou: quem chama segue sem a imagem.
     */
    java.util.Optional<ImageData> read(String url);

    record ImageData(byte[] bytes, String contentType, String extension) {
    }

    /** Remove uma imagem previamente armazenada. Falhas são registradas e não propagadas. */
    void delete(String url);
}
