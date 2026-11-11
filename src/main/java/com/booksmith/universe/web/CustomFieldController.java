package com.booksmith.universe.web;

import com.booksmith.identity.api.CurrentUser;
import com.booksmith.universe.internal.CustomFieldService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Campos próprios do autor (§4.2 do produto): {@code GET} traz os campos de todos os tipos de ficha do universo
 * ({@code {"character": [...], "place": [...], "creature": [...]}}); {@code PUT} troca os de um tipo.
 */
@RestController
@RequestMapping("/api/v1/universes/{universeId}/custom-fields")
@RequiredArgsConstructor
class CustomFieldController {

    private final CustomFieldService customFieldService;
    private final CurrentUser currentUser;

    @GetMapping
    JsonNode list(@PathVariable UUID universeId) {
        return customFieldService.definition(currentUser.id(), universeId);
    }

    @PutMapping("/{scope}")
    JsonNode define(@PathVariable UUID universeId, @PathVariable String scope,
                    @RequestBody @Valid DefineRequest request) {
        return customFieldService.define(currentUser.id(), universeId, scope, request.fields());
    }

    record DefineRequest(@NotNull JsonNode fields) {
    }
}
