package com.booksmith.assist.internal.illustration;

import java.util.List;

/** Quem desenha: o FLUX (Black Forest Labs) ou uma imagem de teste sem custo. */
interface ImageModel {

    String provider();

    String model();

    /**
     * Gera uma imagem.
     *
     * @param references imagens de referência (retratos dos personagens): URL pública (https) ou os bytes em base64
     * @throws com.booksmith.common.error.ApiException 422 {@code illustration_moderated} se o filtro de conteúdo
     *                                                 recusar; 502 {@code illustration_failed} se o fornecedor falhar
     */
    Generated generate(String prompt, int width, int height, List<String> references);

    /** {@code costCredits}: o que o fornecedor cobrou (null se não informou). */
    record Generated(byte[] bytes, String contentType, Double costCredits) {
    }
}
