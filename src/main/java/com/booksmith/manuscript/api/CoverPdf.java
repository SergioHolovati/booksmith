package com.booksmith.manuscript.api;

/**
 * Capa que o autor fez fora do app e importou em PDF.
 *
 * @param url      o PDF original, no armazenamento de PDFs
 * @param kind     {@code front} (só a frente) ou {@code wrap} (capa aberta: contracapa + lombada + frente)
 * @param widthMm  largura da primeira página
 * @param heightMm altura da primeira página
 * @param fileName nome do arquivo enviado, para mostrar na tela
 */
public record CoverPdf(String url, String kind, double widthMm, double heightMm, String fileName) {

    public boolean wrap() {
        return "wrap".equals(kind);
    }
}
