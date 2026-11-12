package com.booksmith.assist.internal;

import java.util.List;

/**
 * O modelo de linguagem por trás das sugestões. Trocar de provedor é trocar a implementação (configuração), sem
 * mexer nas telas nem nos textos de instrução.
 */
public interface IdeaModel {

    /** Nome curto do provedor ({@code ollama}, {@code anthropic}, {@code off}). */
    String provider();

    /** O modelo usado, para o registro de uso e a tela. */
    String model();

    /** {@code true} quando o texto não sai do computador (aviso de privacidade na tela). */
    boolean local();

    /**
     * Pede as ideias. Falha do provedor → {@code ApiException} 503 com mensagem para o autor.
     */
    List<Idea> ideas(Prompt prompt);

    /** Instrução fixa ({@code system}) e o pedido com os dados do autor ({@code user}). */
    record Prompt(String system, String user) {
    }

    record Idea(String title, String text) {
    }

    /** Formato pedido ao modelo (saída estruturada). */
    record IdeaList(List<Idea> suggestions) {
    }
}
