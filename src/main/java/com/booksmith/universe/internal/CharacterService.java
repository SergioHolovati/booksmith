package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Personagens. Tudo é do dono do universo: de outra pessoa, responde 404. */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterService {

    static final int MAX_CHARACTERS = 500;

    private final UniverseRepository universeRepository;
    private final UniverseEntityRepository entityRepository;
    private final AppearanceForm appearanceForm;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<UniverseEntity> list(UUID ownerId, UUID universeId) {
        requireUniverse(ownerId, universeId);
        return entityRepository.findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(
                universeId, UniverseEntity.CHARACTER);
    }

    @Transactional
    public UniverseEntity create(UUID ownerId, UUID universeId, String name) {
        requireUniverse(ownerId, universeId);
        if (list(ownerId, universeId).size() >= MAX_CHARACTERS) {
            throw ApiException.unprocessable("too_many_characters",
                    "Este universo chegou a %d personagens.".formatted(MAX_CHARACTERS));
        }
        return entityRepository.save(UniverseEntity.builder()
                .universeId(universeId)
                .kind(UniverseEntity.CHARACTER)
                .name(requireName(name))
                .build());
    }

    @Transactional(readOnly = true)
    public UniverseEntity get(UUID ownerId, UUID characterId) {
        return require(ownerId, characterId);
    }

    /**
     * Salva a ficha inteira. {@code expectedVersion} é a versão que o app abriu: se outro aparelho salvou
     * antes, responde 409 com a versão atual, e o app avisa em vez de sobrescrever.
     */
    @Transactional
    public UniverseEntity update(UUID ownerId, UUID characterId, long expectedVersion, CharacterData data) {
        UniverseEntity character = require(ownerId, characterId);
        if (character.getVersion() != expectedVersion) {
            throw ApiException.conflict("character_version_conflict",
                    "Este personagem foi alterado em outro aparelho.",
                    Map.of("currentVersion", character.getVersion()));
        }
        character.setName(requireName(data.name()));
        character.setAliases(CharacterSheet.aliases(data.aliases()));
        character.setAttributes(jsonMapper.writeValueAsString(CharacterSheet.normalize(data.sheet(), jsonMapper)));
        character.setAppearance(jsonMapper.writeValueAsString(appearanceForm.normalize(data.appearance(), jsonMapper)));
        character.setAppearanceFormVersion(AppearanceForm.VERSION);
        return entityRepository.saveAndFlush(character);
    }

    @Transactional
    public void delete(UUID ownerId, UUID characterId) {
        require(ownerId, characterId).setDeletedAt(Instant.now(clock));
        log.info("Personagem {} excluído", characterId);
    }

    public String appearanceSummary(UniverseEntity character) {
        return appearanceForm.summary(jsonMapper.readTree(character.getAppearance()));
    }

    private void requireUniverse(UUID ownerId, UUID universeId) {
        universeRepository.findByIdAndOwnerId(universeId, ownerId)
                .orElseThrow(() -> ApiException.notFound("universe_not_found", "Universo não encontrado."));
    }

    private UniverseEntity require(UUID ownerId, UUID characterId) {
        UniverseEntity character = entityRepository.findByIdAndKindAndDeletedAtIsNull(characterId, UniverseEntity.CHARACTER)
                .orElseThrow(CharacterService::notFound);
        if (universeRepository.findByIdAndOwnerId(character.getUniverseId(), ownerId).isEmpty()) {
            throw notFound();
        }
        return character;
    }

    private static String requireName(String name) {
        return EntityNames.require(name, "character_invalid", "Dê um nome ao personagem.");
    }

    private static ApiException notFound() {
        return ApiException.notFound("character_not_found", "Personagem não encontrado.");
    }

    public record CharacterData(String name, List<String> aliases, JsonNode sheet, JsonNode appearance) {
    }
}
