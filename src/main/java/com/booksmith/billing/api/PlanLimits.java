package com.booksmith.billing.api;

import java.time.Duration;

/**
 * Os limites de um plano.
 *
 * @param books              livros (fora da lixeira); {@link #UNLIMITED} = sem limite
 * @param universes          universos (cada livro nasce com o seu)
 * @param charactersPerUniverse personagens em cada universo
 * @param aiUses             usos de IA (revisão, ideias, pergunte ao livro) na janela {@code aiWindow}
 * @param aiWindow           24 horas nos planos pagos; 7 dias no Grátis
 * @param illustrationsPerWeek ilustrações com IA nos últimos 7 dias (~4/semana = ~15/mês no Escritor)
 * @param epub               exportar em ePub
 * @param print              PDF para gráfica (PDF/X, CMYK) e capa completa para impressão
 */
public record PlanLimits(int books, int universes, int charactersPerUniverse, int aiUses, Duration aiWindow,
                         int illustrationsPerWeek, boolean epub, boolean print) {

    public static final int UNLIMITED = Integer.MAX_VALUE;
}
