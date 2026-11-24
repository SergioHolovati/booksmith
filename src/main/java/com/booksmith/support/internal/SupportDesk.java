package com.booksmith.support.internal;

import com.booksmith.common.error.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * O atendimento do lado da equipe (backoffice): caixa de entrada, conversa completa (com as notas internas),
 * resposta (a pessoa recebe por e-mail e vê em "Meus pedidos") e situação do pedido.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupportDesk {

    static final Set<String> STATUSES = Set.of(SupportService.OPEN, SupportService.WAITING_USER, SupportService.RESOLVED);

    private final SupportRequestRepository requests;
    private final SupportMessageRepository messages;
    private final SupportMailer mailer;
    private final Clock clock;

    /** Os pedidos de uma situação (null: todos), do que mexeu por último ao mais antigo. Alta prioridade primeiro. */
    @Transactional(readOnly = true)
    public List<SupportRequest> inbox(String status) {
        if (status != null && !STATUSES.contains(status)) {
            throw ApiException.badRequest("support_invalid_status", "Situação desconhecida.");
        }
        List<SupportRequest> found = status == null
                ? requests.findTop200ByOrderByLastActivityAtDesc()
                : requests.findTop200ByStatusOrderByLastActivityAtDesc(status);
        if (!SupportService.OPEN.equals(status)) {
            return found;
        }
        // Esperando a equipe: texto sumido e privacidade na frente (a ordenação é estável)
        return found.stream()
                .sorted(Comparator.comparing((SupportRequest r) -> !"high".equals(r.getPriority())))
                .toList();
    }

    /** Quantos pedidos em cada situação (as abas da caixa de entrada). */
    @Transactional(readOnly = true)
    public Map<String, Long> counts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String status : List.of(SupportService.OPEN, SupportService.WAITING_USER, SupportService.RESOLVED)) {
            counts.put(status, requests.countByStatus(status));
        }
        return counts;
    }

    @Transactional(readOnly = true)
    public SupportService.Conversation conversation(UUID requestId) {
        return new SupportService.Conversation(find(requestId), messages.findAllByRequestIdOrderByCreatedAtAsc(requestId));
    }

    /**
     * Resposta da equipe. Para a pessoa: o pedido passa a esperar por ela e ela recebe o e-mail. Nota interna: só a
     * equipe vê, nada muda para a pessoa.
     */
    @Transactional
    public SupportService.Conversation reply(UUID adminId, UUID requestId, String body, boolean internal) {
        SupportRequest request = find(requestId);
        String text = SupportService.text(body);
        messages.save(SupportMessage.builder().requestId(requestId).author(SupportMessage.STAFF).authorId(adminId)
                .body(text).internal(internal).build());
        if (!internal) {
            request.setStatus(SupportService.WAITING_USER);
            request.setLastActivityAt(Instant.now(clock));
            mailer.staffReplied(request, text);
            log.info("Suporte: pedido {} respondido por {}", request.protocol(), adminId);
        }
        return new SupportService.Conversation(request, messages.findAllByRequestIdOrderByCreatedAtAsc(requestId));
    }

    @Transactional
    public SupportRequest status(UUID adminId, UUID requestId, String status) {
        if (status == null || !STATUSES.contains(status)) {
            throw ApiException.badRequest("support_invalid_status", "Situação desconhecida.");
        }
        SupportRequest request = find(requestId);
        request.setStatus(status);
        request.setLastActivityAt(Instant.now(clock));
        log.info("Suporte: pedido {} ficou {} ({})", request.protocol(), status, adminId);
        return request;
    }

    private SupportRequest find(UUID requestId) {
        return requests.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("support_request_not_found", "Pedido não encontrado."));
    }
}
