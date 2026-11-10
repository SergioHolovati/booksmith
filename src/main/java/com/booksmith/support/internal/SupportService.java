package com.booksmith.support.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.api.AuthenticatedUser;
import com.booksmith.identity.api.UserDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pedidos de suporte (§20.6): guarda a mensagem com o contexto técnico, define a prioridade e abre o ticket na
 * ferramenta de atendimento. Texto sumido e privacidade (LGPD, responsáveis) têm prioridade alta.
 */
@Service
@RequiredArgsConstructor
public class SupportService {

    static final Set<String> CATEGORIES = Set.of("lost_text", "account", "import_export", "universe", "bug",
            "suggestion", "privacy", "other");
    static final Set<String> HIGH_PRIORITY = Set.of("lost_text", "privacy");
    static final int MAX_MESSAGE = 5_000;
    static final int MIN_MESSAGE = 10;
    /** Por pessoa, em 24 horas: mais que isso é quase sempre erro do app (envio repetido) ou abuso. */
    static final int MAX_PER_DAY = 10;

    private final SupportRequestRepository repository;
    private final HelpdeskGateway helpdesk;
    private final UserDirectory users;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Transactional
    public SupportRequest create(AuthenticatedUser user, String category, String message, SupportContext context) {
        if (category == null || !CATEGORIES.contains(category)) {
            throw ApiException.unprocessable("support_invalid", "Escolha o assunto do pedido.");
        }
        String text = message == null ? "" : message.strip();
        if (text.length() < MIN_MESSAGE) {
            throw ApiException.unprocessable("support_invalid", "Conte um pouco mais do que aconteceu.");
        }
        if (text.length() > MAX_MESSAGE) {
            throw ApiException.unprocessable("support_invalid",
                    "A mensagem passou de %d caracteres.".formatted(MAX_MESSAGE));
        }
        Instant dayAgo = Instant.now(clock).minus(Duration.ofDays(1));
        if (repository.countByUserIdAndCreatedAtAfter(user.id(), dayAgo) >= MAX_PER_DAY) {
            throw ApiException.unprocessable("too_many_support_requests",
                    "Você já enviou muitos pedidos hoje. A equipe responde todos pelo e-mail da conta.");
        }
        String email = users.email(user.id())
                .orElseThrow(() -> ApiException.unauthorized("Entre de novo para falar com o suporte."));
        SupportContext clean = (context == null ? SupportContext.empty() : context).clean();
        SupportRequest request = repository.save(SupportRequest.builder()
                .userId(user.id())
                .email(email)
                .category(category)
                .message(text)
                .context(jsonMapper.writeValueAsString(clean))
                .priority(HIGH_PRIORITY.contains(category) ? "high" : "normal")
                .build());
        request.setHelpdeskRef(helpdesk.open(request));
        return request;
    }

    @Transactional(readOnly = true)
    public List<SupportRequest> mine(UUID userId) {
        return repository.findTop50ByUserIdOrderByCreatedAtDesc(userId);
    }
}
