package com.booksmith.manuscript.internal;

/**
 * O gênero do livro: decide as ferramentas que aparecem (Universo, Personagens e Linha do tempo só fazem sentido na
 * ficção e no infantil), os modelos de estrutura e, no {@link #CHILDREN}, o modo livro ilustrado (cada capítulo é uma
 * página, com a ilustração de fundo e o texto por cima).
 */
public enum BookGenre {
    FICTION,
    CHRISTIAN,
    SELF_HELP,
    SPIRITUAL,
    EDUCATIONAL,
    CHILDREN,
    OTHER
}
