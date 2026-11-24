package com.booksmith.support.internal;

import com.booksmith.notification.api.EmailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Os e-mails do atendimento. Para a equipe, só o protocolo, o assunto e o link (o que a pessoa escreveu fica no
 * backoffice, não na caixa de e-mail). Para a pessoa, a resposta da equipe. Falha no envio não desfaz a mensagem:
 * fica no log e a conversa segue no app.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class SupportMailer {

    static final Map<String, String> CATEGORY = Map.of(
            "lost_text", "Texto sumido", "account", "Conta e acesso", "import_export", "Importar e exportar",
            "universe", "Universo", "bug", "Erro no app", "suggestion", "Sugestão", "privacy", "Privacidade",
            "other", "Outro assunto");

    private final EmailSender emailSender;
    private final SupportProperties properties;

    void newRequest(SupportRequest request) {
        toTeam("Pedido de suporte " + request.protocol(),
                "Pedido novo: %s, prioridade %s.%n%nAbra no backoffice: %s".formatted(
                        CATEGORY.getOrDefault(request.getCategory(), request.getCategory()),
                        "high".equals(request.getPriority()) ? "alta" : "normal", link(request)));
    }

    void userReplied(SupportRequest request) {
        toTeam("Resposta no pedido " + request.protocol(),
                "A pessoa respondeu no pedido %s.%n%nAbra no backoffice: %s".formatted(request.protocol(), link(request)));
    }

    void staffReplied(SupportRequest request, String body) {
        String app = properties.appUrl() == null ? "" : properties.appUrl() + "/ajuda/pedidos";
        send(request.getEmail(), "Resposta ao seu pedido " + request.protocol(),
                "%s%n%n---%nPara responder, abra o app em Ajuda > Meus pedidos: %s".formatted(body, app));
    }

    private String link(SupportRequest request) {
        String base = properties.adminUrl() == null ? "" : properties.adminUrl();
        return base + "/suporte/" + request.getId();
    }

    private void toTeam(String title, String message) {
        if (properties.teamEmail() == null || properties.teamEmail().isBlank()) {
            log.info("Suporte: {} (sem booksmith.support.team-email, só o log)", title);
            return;
        }
        send(properties.teamEmail(), title, message);
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void send(String to, String title, String message) {
        try {
            emailSender.sendNotice(to, title, message);
        } catch (RuntimeException e) {
            log.warn("Suporte: e-mail \"{}\" não foi enviado ({})", title, e.getMessage());
        }
    }
}
