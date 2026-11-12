package com.booksmith.assist.internal;

import java.util.List;

/**
 * O modelo de linguagem por trás das sugestões e da revisão. Trocar de provedor é trocar a implementação
 * (configuração), sem mexer nas telas nem nos textos de instrução.
 */
public interface IdeaModel {

    /** Nome curto do provedor ({@code anthropic} ou {@code off}). */
    String provider();

    /** O modelo usado, para o registro de uso e a tela. */
    String model();

    /** {@code true} quando o texto não sai do computador (aviso de privacidade na tela). */
    boolean local();

    /** Pede as ideias. Falha do provedor → {@code ApiException} 503 com mensagem para o autor. */
    List<Idea> ideas(Prompt prompt);

    /** Pede os apontamentos da revisão de um trecho. Falha do provedor → {@code ApiException} 503. */
    List<Finding> findings(Prompt prompt);

    /** Responde uma pergunta do autor sobre o livro, com os trechos de origem. Falha → {@code ApiException} 503. */
    Answer answer(Prompt prompt);

    /**
     * Instrução fixa ({@code system}), o pedido com os dados do autor ({@code user}) e a temperatura para o
     * modelo local (baixa na revisão, para não inventar; o Claude usa a dele).
     */
    record Prompt(String system, String user, double temperature) {

        public Prompt(String system, String user) {
            this(system, user, 0.6);
        }
    }

    record Idea(String title, String text) {
    }

    /** Formato pedido ao modelo nas sugestões (saída estruturada). */
    record IdeaList(List<Idea> suggestions) {
    }

    /**
     * Um apontamento da revisão.
     *
     * @param quote      o trecho exato do texto ao qual se refere (pode ficar vazio, no capítulo inteiro)
     * @param problem    o que está errado ou poderia melhorar
     * @param suggestion na ortografia, como fica corrigido; nos outros, o que o autor pode conferir ou mudar
     */
    record Finding(String quote, String problem, String suggestion) {
    }

    /** Formato pedido ao modelo na revisão (saída estruturada). */
    record FindingList(List<Finding> findings) {
    }

    /**
     * Resposta a "Pergunte ao seu livro" (saída estruturada).
     *
     * @param answer  a resposta, curta, só com o que está no material
     * @param sources os trechos do texto que sustentam a resposta
     */
    record Answer(String answer, List<Source> sources) {
    }

    /**
     * @param chapter o número do capítulo de onde veio o trecho
     * @param quote   o trecho copiado do texto, palavra por palavra
     */
    record Source(int chapter, String quote) {
    }
}
