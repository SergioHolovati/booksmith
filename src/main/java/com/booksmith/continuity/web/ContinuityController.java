package com.booksmith.continuity.web;

import com.booksmith.continuity.internal.Alert;
import com.booksmith.continuity.internal.Analysis;
import com.booksmith.continuity.internal.ContinuityService;
import com.booksmith.identity.api.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Continuidade (§10 da arquitetura). Por enquanto, verificações sem IA: cor de olhos e cabelo contra a ficha,
 * e grafia de nomes. "Analisar" roda na hora e é de graça; não consome créditos.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
class ContinuityController {

    /** O que a análise atual confere: a tela mostra isso, para o autor saber o que (ainda) não é verificado. */
    static final List<String> CHECKS = List.of(
            "Cor dos olhos e do cabelo no texto × ficha do personagem",
            "Nomes escritos de jeito diferente (\"Kael\" × \"Kaell\")");

    private final ContinuityService continuityService;
    private final CurrentUser currentUser;
    private final JsonMapper jsonMapper;

    @GetMapping("/books/{bookId}/continuity")
    ContinuityResponse overview(@PathVariable UUID bookId) {
        var overview = continuityService.overview(currentUser.id(), bookId);
        return new ContinuityResponse(overview.lastAnalysis().map(this::analysis).orElse(null),
                overview.openAlerts().stream().map(this::alert).toList(), CHECKS, null, null);
    }

    @PostMapping("/books/{bookId}/continuity/analyze")
    ContinuityResponse analyze(@PathVariable UUID bookId) {
        var result = continuityService.analyze(currentUser.id(), bookId);
        return new ContinuityResponse(analysis(result.analysis()), result.openAlerts().stream().map(this::alert).toList(),
                CHECKS, result.created(), result.fixed());
    }

    /** {@code status}: open, ignored ou intentional. */
    @PatchMapping("/continuity/alerts/{alertId}")
    AlertResponse setStatus(@PathVariable UUID alertId, @RequestBody @Valid StatusRequest request) {
        return alert(continuityService.setStatus(currentUser.id(), alertId, request.status()));
    }

    private AnalysisResponse analysis(Analysis analysis) {
        return new AnalysisResponse(analysis.getAnalyzedAt(), analysis.getChapters(), analysis.getEntities());
    }

    private AlertResponse alert(Alert alert) {
        return new AlertResponse(alert.getId(), alert.getType(), alert.getSeverity(), alert.getConfidence(),
                alert.getMessage(), jsonMapper.readTree(alert.getEvidence()), alert.getStatus(), alert.getCreatedAt());
    }

    record StatusRequest(@NotBlank String status) {
    }

    record AnalysisResponse(Instant analyzedAt, int chapters, int entities) {
    }

    record AlertResponse(UUID id, String type, String severity, BigDecimal confidence, String message,
                         JsonNode evidence, String status, Instant createdAt) {
    }

    /** {@code created}/{@code fixed} só vêm na resposta de uma análise. */
    record ContinuityResponse(AnalysisResponse lastAnalysis, List<AlertResponse> alerts, List<String> checks,
                              Integer created, Integer fixed) {
    }
}
