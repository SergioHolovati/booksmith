package com.booksmith.universe.web;

import com.booksmith.identity.api.CurrentUser;
import com.booksmith.universe.internal.Universe;
import com.booksmith.universe.internal.UniverseForm;
import com.booksmith.universe.internal.UniverseService;
import com.booksmith.universe.internal.UniverseService.UniverseWithCounts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Universos do autor (§4.2 do produto) e o construtor de universo. Os livros de cada universo vêm de
 * {@code GET /books} (campo {@code universeId}); entrar num universo é pelo livro: {@code PUT /books/{id}/universe}.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
class UniverseController {

    private static final int PREMISE_EXCERPT = 160;

    private final UniverseService universeService;
    private final UniverseForm universeForm;
    private final CurrentUser currentUser;

    /** Passos, campos, opções e coleções do construtor. O app monta a tela a partir disto. */
    @GetMapping("/forms/universe-builder")
    JsonNode builderForm() {
        return universeForm.definition();
    }

    @GetMapping("/universes")
    List<UniverseSummary> list() {
        return universeService.list(currentUser.id()).stream().map(this::summary).toList();
    }

    /** Universo sem livro ainda: quem cria o mundo antes da história. */
    @PostMapping("/universes")
    @ResponseStatus(HttpStatus.CREATED)
    UniverseResponse create(@RequestBody @Valid NameRequest request) {
        UUID id = universeService.create(currentUser.id(), request.name());
        return response(universeService.get(currentUser.id(), id));
    }

    @GetMapping("/universes/{universeId}")
    UniverseResponse get(@PathVariable UUID universeId) {
        return response(universeService.get(currentUser.id(), universeId));
    }

    @PutMapping("/universes/{universeId}")
    UniverseSummary rename(@PathVariable UUID universeId, @RequestBody @Valid NameRequest request) {
        universeService.rename(currentUser.id(), universeId, request.name());
        return summary(universeService.get(currentUser.id(), universeId));
    }

    @PutMapping("/universes/{universeId}/details")
    UniverseResponse saveDetails(@PathVariable UUID universeId, @RequestBody @Valid DetailsRequest request) {
        universeService.saveDetails(currentUser.id(), universeId, request.version(), request.name(), request.details());
        return response(universeService.get(currentUser.id(), universeId));
    }

    @PutMapping("/universes/{universeId}/map")
    UniverseResponse setMap(@PathVariable UUID universeId, @RequestBody @Valid MapRequest request) {
        universeService.setMap(currentUser.id(), universeId, request.image(), request.generator());
        return response(universeService.get(currentUser.id(), universeId));
    }

    @DeleteMapping("/universes/{universeId}/map")
    UniverseResponse removeMap(@PathVariable UUID universeId) {
        universeService.removeMap(currentUser.id(), universeId);
        return response(universeService.get(currentUser.id(), universeId));
    }

    private UniverseSummary summary(UniverseWithCounts u) {
        JsonNode details = universeService.details(u.universe());
        String premise = details.path("premise").asString("");
        if (premise.length() > PREMISE_EXCERPT) {
            int cut = premise.lastIndexOf(' ', PREMISE_EXCERPT);
            premise = premise.substring(0, cut > 40 ? cut : PREMISE_EXCERPT).strip() + "…";
        }
        return new UniverseSummary(u.universe().getId(), u.universe().getName(), premise.isEmpty() ? null : premise,
                u.universe().getMapUrl(), u.characters(), u.places(), u.events(),
                universeForm.count(details, "magic"), universeForm.count(details, "laws"), u.universe().getCreatedAt());
    }

    private UniverseResponse response(UniverseWithCounts u) {
        Universe universe = u.universe();
        return new UniverseResponse(universe.getId(), universe.getName(), universeService.details(universe),
                UniverseForm.VERSION, universe.getMapUrl(), universeService.mapGenerator(universe), universe.getVersion(),
                u.characters(), u.places(),
                u.events(), universe.getCreatedAt(), universe.getUpdatedAt());
    }

    record NameRequest(@NotBlank(message = "Dê um nome ao universo.") @Size(max = 120) String name) {
    }

    record DetailsRequest(@NotNull Long version,
                          @NotBlank(message = "Dê um nome ao universo.") @Size(max = 120) String name,
                          JsonNode details) {
    }

    /** {@code generator}: semente e ajustes, quando o mapa foi gerado no app. */
    record MapRequest(@NotBlank(message = "Escolha a imagem do mapa.") String image, JsonNode generator) {
    }

    record UniverseSummary(UUID id, String name, String premise, String mapUrl, long characterCount, long placeCount,
                           long eventCount, int magicCount, int lawCount, Instant createdAt) {
    }

    record UniverseResponse(UUID id, String name, JsonNode details, int formVersion, String mapUrl,
                            JsonNode mapGenerator, long version,
                            long characterCount, long placeCount, long eventCount, Instant createdAt,
                            Instant updatedAt) {
    }
}
