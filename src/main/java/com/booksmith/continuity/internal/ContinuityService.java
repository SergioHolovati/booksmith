package com.booksmith.continuity.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.api.Manuscripts;
import com.booksmith.manuscript.api.Manuscripts.BookText;
import com.booksmith.universe.api.Universes;
import com.booksmith.universe.api.Universes.KnownEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Análise de continuidade do livro inteiro com as verificações determinísticas. Cada análise compara o que
 * encontrou com os alertas guardados (pela impressão digital):
 * <ul>
 *   <li>problema novo → alerta aberto;</li>
 *   <li>problema que continua → alerta atualizado, com a decisão do autor preservada;</li>
 *   <li>alerta aberto que não aparece mais → corrigido ({@code fixed}), sem o autor precisar fazer nada.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContinuityService {

    private final Manuscripts manuscripts;
    private final Universes universes;
    private final AlertRepository alertRepository;
    private final AnalysisRepository analysisRepository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Transactional
    public Result analyze(UUID ownerId, UUID bookId) {
        BookText book = manuscripts.bookText(ownerId, bookId);
        List<KnownEntity> entities = book.universeId() == null ? List.of() : universes.entities(book.universeId());

        List<Finding> findings = new ArrayList<>();
        findings.addAll(AttributeCheck.run(book, entities));
        findings.addAll(NamingCheck.run(book, entities));

        Instant now = Instant.now(clock);
        Map<String, Alert> existing = alertRepository.findAllByBookId(bookId).stream()
                .collect(Collectors.toMap(Alert::getFingerprint, Function.identity()));
        Set<String> seen = new java.util.HashSet<>();
        int created = 0;
        for (Finding finding : findings) {
            seen.add(finding.fingerprint());
            Alert alert = existing.get(finding.fingerprint());
            if (alert == null) {
                alert = Alert.builder().bookId(bookId).fingerprint(finding.fingerprint()).status(Alert.OPEN).build();
                created++;
            } else if (Alert.FIXED.equals(alert.getStatus())) {
                alert.setStatus(Alert.OPEN); // voltou a aparecer no texto
                alert.setResolvedAt(null);
            }
            alert.setType(finding.type());
            alert.setSeverity(finding.severity());
            alert.setConfidence(finding.confidence());
            alert.setMessage(finding.message());
            alert.setEvidence(jsonMapper.writeValueAsString(finding.evidence()));
            alertRepository.save(alert);
        }
        int fixed = 0;
        for (Alert alert : existing.values()) {
            if (Alert.OPEN.equals(alert.getStatus()) && !seen.contains(alert.getFingerprint())) {
                alert.setStatus(Alert.FIXED);
                alert.setResolvedAt(now);
                fixed++;
            }
        }
        Analysis analysis = analysisRepository.findById(bookId).orElseGet(Analysis::new);
        analysis.setBookId(bookId);
        analysis.setAnalyzedAt(now);
        analysis.setChapters(book.chapters().size());
        analysis.setEntities(entities.size());
        analysisRepository.save(analysis);
        alertRepository.flush();

        log.info("Análise de continuidade do livro {}: {} achados, {} novos, {} corrigidos", bookId, findings.size(),
                created, fixed);
        return new Result(analysis, open(bookId), created, fixed);
    }

    @Transactional(readOnly = true)
    public Overview overview(UUID ownerId, UUID bookId) {
        manuscripts.requireOwned(ownerId, bookId);
        return new Overview(analysisRepository.findById(bookId), open(bookId));
    }

    /** O autor decide: aberto, ignorado ou intencional. Alertas de outra pessoa respondem 404. */
    @Transactional
    public Alert setStatus(UUID ownerId, UUID alertId, String status) {
        if (!Alert.AUTHOR_STATUSES.contains(status)) {
            throw ApiException.unprocessable("alert_status_invalid", "Use open, ignored ou intentional.");
        }
        Alert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> ApiException.notFound("alert_not_found", "Alerta não encontrado."));
        try {
            manuscripts.requireOwned(ownerId, alert.getBookId());
        } catch (ApiException e) {
            throw ApiException.notFound("alert_not_found", "Alerta não encontrado.");
        }
        alert.setStatus(status);
        alert.setResolvedAt(Alert.OPEN.equals(status) ? null : Instant.now(clock));
        return alertRepository.saveAndFlush(alert);
    }

    private List<Alert> open(UUID bookId) {
        return alertRepository.findAllByBookIdAndStatusInOrderByCreatedAtAsc(bookId, List.of(Alert.OPEN));
    }

    public record Result(Analysis analysis, List<Alert> openAlerts, int created, int fixed) {
    }

    public record Overview(Optional<Analysis> lastAnalysis, List<Alert> openAlerts) {
    }
}
