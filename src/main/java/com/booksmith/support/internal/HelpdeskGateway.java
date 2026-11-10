package com.booksmith.support.internal;

/**
 * Ferramenta de atendimento (questão em aberto, §32): cria o ticket a partir do pedido. Como os outros
 * provedores, fica atrás desta interface; a implementação escolhida substitui {@link LoggingHelpdeskGateway}.
 */
public interface HelpdeskGateway {

    /** Cria o ticket e devolve a referência dele na ferramenta, ou null se não houver ferramenta. */
    String open(SupportRequest request);
}
