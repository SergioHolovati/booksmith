package com.booksmith.assist.internal;

import com.booksmith.common.data.PersonalDataArchive;
import com.booksmith.common.data.PersonalDataContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** "Baixar tudo": quando a pessoa pediu sugestões de IA, para o quê e a qual provedor (o texto não é guardado). */
@Component
@RequiredArgsConstructor
class AssistDataContributor implements PersonalDataContributor {

    private final SuggestionLogRepository repository;

    @Override
    public String name() {
        return "sugestões de IA";
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(UUID ownerId, PersonalDataArchive archive) {
        var logs = repository.findAllByUserIdOrderByCreatedAtDesc(ownerId);
        if (logs.isEmpty()) {
            return;
        }
        archive.json("sugestoes-de-ia/pedidos.json", logs.stream()
                .map(l -> new UsageData(l.getKind(), l.getField(), l.getMode(), l.getProvider(), l.getModel(),
                        l.getCreatedAt()))
                .toList());
    }

    record UsageData(String kind, String field, String mode, String provider, String model, Instant createdAt) {
    }
}
