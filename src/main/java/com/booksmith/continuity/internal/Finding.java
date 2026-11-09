package com.booksmith.continuity.internal;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Um possível furo encontrado numa análise. {@code fingerprint} identifica o mesmo problema entre análises:
 * é o que permite manter a decisão do autor (ignorado, intencional) e fechar sozinho o que foi corrigido.
 */
record Finding(String type, String severity, BigDecimal confidence, String message, List<Evidence> evidence,
               String fingerprint) {

    /** Trecho do livro que sustenta o alerta; {@code highlight} é a parte a destacar dentro da citação. */
    record Evidence(UUID chapterId, String chapterTitle, String blockId, String quote, String highlight) {
    }
}
