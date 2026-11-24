package com.booksmith.support.web;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.api.CurrentUser;
import com.booksmith.identity.api.UserDirectory;
import com.booksmith.support.internal.SupportDesk;
import com.booksmith.support.internal.SupportRequest;
import com.booksmith.support.internal.SupportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Backoffice: o atendimento. Caixa de entrada por situação, a conversa com o contexto técnico que o app mandou,
 * resposta (ou nota interna) e mudança de situação. Só administradores (403 {@code admin_only}).
 */
@RestController
@RequestMapping("/api/v1/admin/support")
@RequiredArgsConstructor
class SupportAdminController {

    private static final int PREVIEW = 140;

    private final SupportDesk desk;
    private final UserDirectory users;
    private final CurrentUser currentUser;
    private final JsonMapper jsonMapper;

    /** Uma linha da caixa de entrada: o começo da mensagem, sem o contexto. */
    record InboxItem(UUID id, String protocol, String category, String priority, String status, String email,
                     String preview, Instant createdAt, Instant lastActivityAt) {
    }

    record InboxResponse(Map<String, Long> counts, List<InboxItem> requests) {
    }

    /** {@code internal}: nota da equipe, que a pessoa não vê. */
    record MessageView(UUID id, String author, String body, boolean internal, Instant createdAt) {
    }

    record DetailResponse(UUID id, String protocol, String category, String priority, String status, String email,
                          UUID userId, String message, JsonNode context, Instant createdAt, Instant lastActivityAt,
                          List<MessageView> messages) {
    }

    record ReplyRequest(String body, boolean internal) {
    }

    record StatusRequest(String status) {
    }

    @GetMapping("/requests")
    InboxResponse inbox(@RequestParam(required = false) String status) {
        requireAdmin();
        return new InboxResponse(desk.counts(), desk.inbox(status == null || status.isBlank() ? null : status).stream()
                .map(r -> new InboxItem(r.getId(), r.protocol(), r.getCategory(), r.getPriority(), r.getStatus(),
                        r.getEmail(), preview(r.getMessage()), r.getCreatedAt(), r.getLastActivityAt()))
                .toList());
    }

    @GetMapping("/requests/{id}")
    DetailResponse detail(@PathVariable UUID id) {
        requireAdmin();
        return detail(desk.conversation(id));
    }

    @PostMapping("/requests/{id}/messages")
    DetailResponse reply(@PathVariable UUID id, @RequestBody ReplyRequest request) {
        UUID admin = requireAdmin();
        return detail(desk.reply(admin, id, request.body(), request.internal()));
    }

    @PostMapping("/requests/{id}/status")
    DetailResponse status(@PathVariable UUID id, @RequestBody StatusRequest request) {
        UUID admin = requireAdmin();
        desk.status(admin, id, request.status());
        return detail(desk.conversation(id));
    }

    private DetailResponse detail(SupportService.Conversation conversation) {
        SupportRequest r = conversation.request();
        return new DetailResponse(r.getId(), r.protocol(), r.getCategory(), r.getPriority(), r.getStatus(),
                r.getEmail(), r.getUserId(), r.getMessage(), jsonMapper.readTree(r.getContext()), r.getCreatedAt(),
                r.getLastActivityAt(), conversation.messages().stream()
                        .map(m -> new MessageView(m.getId(), m.getAuthor(), m.getBody(), m.isInternal(), m.getCreatedAt()))
                        .toList());
    }

    static String preview(String message) {
        String flat = message.replaceAll("\\s+", " ").strip();
        return flat.length() <= PREVIEW ? flat : flat.substring(0, PREVIEW - 1) + "…";
    }

    private UUID requireAdmin() {
        UUID id = currentUser.id();
        if (!users.isAdmin(id)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "admin_only", "Só a equipe do Booksmith atende os pedidos.",
                    Map.of());
        }
        return id;
    }
}
