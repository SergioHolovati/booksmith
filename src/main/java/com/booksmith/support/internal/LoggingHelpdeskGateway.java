package com.booksmith.support.internal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Sem ferramenta de atendimento ainda: o pedido fica no banco ({@code support.requests}) e o aviso vai para o
 * log, sem a mensagem (o log não é lugar para o que o usuário escreveu). Quando a ferramenta for escolhida,
 * a implementação dela substitui esta classe.
 */
@Slf4j
@Component
class LoggingHelpdeskGateway implements HelpdeskGateway {

    @Override
    public String open(SupportRequest request) {
        log.info("Pedido de suporte {} ({}, prioridade {}) de {}", request.protocol(), request.getCategory(),
                request.getPriority(), request.getUserId());
        return null;
    }
}
