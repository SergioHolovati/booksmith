package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

/** Campos próprios de um universo ({@link CustomFields}): ler a definição, salvar a de um tipo, conferir valores. */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomFieldService {

    private final UniverseRepository universeRepository;
    private final CustomFields customFields;
    private final JsonMapper jsonMapper;

    /** A definição inteira, só com os tipos que existem (um tipo removido do sistema não aparece). */
    @Transactional(readOnly = true)
    public ObjectNode definition(UUID ownerId, UUID universeId) {
        JsonNode stored = jsonMapper.readTree(require(ownerId, universeId).getCustomFields());
        ObjectNode result = jsonMapper.createObjectNode();
        for (String scope : customFields.scopes()) {
            result.set(scope, stored.has(scope) ? stored.get(scope) : jsonMapper.createArrayNode());
        }
        return result;
    }

    /** Troca os campos de um tipo de ficha; devolve a lista conferida, com as chaves geradas. */
    @Transactional
    public ArrayNode define(UUID ownerId, UUID universeId, String scope, JsonNode fields) {
        customFields.requireScope(scope);
        Universe universe = require(ownerId, universeId);
        ArrayNode clean = customFields.define(fields, jsonMapper);
        JsonNode stored = jsonMapper.readTree(universe.getCustomFields());
        ObjectNode next = stored.isObject() ? (ObjectNode) stored : jsonMapper.createObjectNode();
        if (clean.isEmpty()) {
            next.remove(scope);
        } else {
            next.set(scope, clean);
        }
        universe.setCustomFields(jsonMapper.writeValueAsString(next));
        log.info("Universo {}: {} campos próprios em {}", universeId, clean.size(), scope);
        return clean;
    }

    /**
     * Grava os valores dos campos próprios na ficha. {@code values} nulo: mantém o que estava (quem salvou não
     * mandou os campos próprios).
     */
    void apply(UniverseEntity entity, String scope, JsonNode values) {
        if (values == null || values.isNull()) {
            return;
        }
        Universe universe = universeRepository.findById(entity.getUniverseId()).orElseThrow();
        JsonNode definition = jsonMapper.readTree(universe.getCustomFields());
        entity.setCustom(jsonMapper.writeValueAsString(customFields.values(definition, scope, values, jsonMapper)));
    }

    /** Valores da ficha que ainda cabem nos campos ({@link CustomFields#current}). */
    @Transactional(readOnly = true)
    public JsonNode values(UniverseEntity entity, String scope) {
        Universe universe = universeRepository.findById(entity.getUniverseId()).orElseThrow();
        JsonNode definition = jsonMapper.readTree(universe.getCustomFields());
        return customFields.current(definition, scope, jsonMapper.readTree(entity.getCustom()), jsonMapper);
    }

    private Universe require(UUID ownerId, UUID universeId) {
        return universeRepository.findByIdAndOwnerId(universeId, ownerId)
                .orElseThrow(() -> ApiException.notFound("universe_not_found", "Universo não encontrado."));
    }
}
