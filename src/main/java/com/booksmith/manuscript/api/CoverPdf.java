package com.booksmith.manuscript.api;

/**
 * Capa que o autor fez fora do app e importou em PDF.
 *
 * @param url      o PDF original, no armazenamento de PDFs
 * @param kind     {@code front} (só a frente) ou {@code wrap} (capa aberta: contracapa + lombada + frente)
 * @param widthMm  largura da primeira página (com as marcas de corte, se houver)
 * @param heightMm altura da primeira página
 * @param fileName nome do arquivo enviado, para mostrar na tela
 * @param trim     o tamanho do livro para o qual a capa foi feita ({@code 16x23}…), quando o autor informou ou o
 *                 PDF diz; null em capas antigas
 * @param spineMm  a lombada da capa (capa aberta), quando se sabe
 * @param bleedMm  a sangria da capa, quando se sabe
 * @param marksMm  a margem das marcas de corte em volta da sangria (0 sem marcas)
 */
public record CoverPdf(String url, String kind, double widthMm, double heightMm, String fileName, String trim,
                       Double spineMm, Double bleedMm, Double marksMm) {

    public CoverPdf(String url, String kind, double widthMm, double heightMm, String fileName) {
        this(url, kind, widthMm, heightMm, fileName, null, null, null, null);
    }

    public boolean wrap() {
        return "wrap".equals(kind);
    }

    /** A margem das marcas de corte (0 quando não há ou não se sabe). */
    public double marks() {
        return marksMm == null ? 0 : marksMm;
    }

    /** A capa com a sangria, sem a margem das marcas de corte: a medida que a gráfica confere. */
    public double boxWidthMm() {
        return Math.round((widthMm - 2 * marks()) * 10) / 10.0;
    }

    public double boxHeightMm() {
        return Math.round((heightMm - 2 * marks()) * 10) / 10.0;
    }
}
