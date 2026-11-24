package com.booksmith.support.internal;

import com.booksmith.common.data.PersonalDataArchive;
import com.booksmith.common.data.PersonalDataContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/** "Baixar tudo": os pedidos de suporte, com o contexto técnico que foi junto. */
@Component
@RequiredArgsConstructor
class SupportDataContributor implements PersonalDataContributor {

    private final SupportRequestRepository repository;
    private final SupportMessageRepository messages;
    private final JsonMapper jsonMapper;

    @Override
    public String name() {
        return "suporte";
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(UUID ownerId, PersonalDataArchive archive) {
        var requests = repository.findAllByUserIdOrderByCreatedAtDesc(ownerId);
        if (requests.isEmpty()) {
            return;
        }
        archive.json("suporte/pedidos.json", requests.stream()
                .map(r -> new RequestData(r.protocol(), r.getCategory(), r.getMessage(), r.getStatus(),
                        jsonMapper.readTree(r.getContext()), r.getCreatedAt(),
                        messages.findAllByRequestIdAndInternalFalseOrderByCreatedAtAsc(r.getId()).stream()
                                .map(m -> new MessageData(m.getAuthor(), m.getBody(), m.getCreatedAt()))
                                .toList()))
                .toList());
    }

    /** A conversa sem as notas internas da equipe. */
    record RequestData(String protocol, String category, String message, String status, JsonNode context,
                       Instant createdAt, java.util.List<MessageData> messages) {
    }

    record MessageData(String author, String body, Instant createdAt) {
    }
}
