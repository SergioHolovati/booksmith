package com.booksmith.support.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * O atendimento é o próprio backoffice: o pedido fica no banco ({@code support.requests}), a equipe recebe um
 * e-mail com o protocolo e o link, e o log registra só os metadados (nunca o que a pessoa escreveu).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class BackofficeHelpdeskGateway implements HelpdeskGateway {

    private final SupportMailer mailer;

    @Override
    public String open(SupportRequest request) {
        log.info("Pedido de suporte {} ({}, prioridade {}) de {}", request.protocol(), request.getCategory(),
                request.getPriority(), request.getUserId());
        mailer.newRequest(request);
        return null;
    }
}
