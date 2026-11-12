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
    private final ReviewRunRepository runs;

    @Override
    public String name() {
        return "sugestões de IA";
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(UUID ownerId, PersonalDataArchive archive) {
        var logs = repository.findAllByUserIdOrderByCreatedAtDesc(ownerId);
        if (!logs.isEmpty()) {
            archive.json("sugestoes-de-ia/pedidos.json", logs.stream()
                    .map(l -> new UsageData(l.getKind(), l.getField(), l.getMode(), l.getProvider(), l.getModel(),
                            l.getCreatedAt()))
                    .toList());
        }
        var reviews = runs.findAllByUserIdOrderByCreatedAtDesc(ownerId);
        if (!reviews.isEmpty()) {
            archive.json("sugestoes-de-ia/revisoes.json", reviews.stream()
                    .map(r -> new ReviewData(r.getChapterId(), r.getCalls(), r.getCreatedAt()))
                    .toList());
        }
    }

    /** Uma revisão com IA aberta: de qual capítulo, quantas chamadas fez e quando. */
    record ReviewData(UUID chapterId, int calls, Instant createdAt) {
    }

    record UsageData(String kind, String field, String mode, String provider, String model, Instant createdAt) {
    }
}
