package com.booksmith.universe.web;

import com.booksmith.identity.api.CurrentUser;
import com.booksmith.universe.internal.GalleryService;
import java.util.Map;
import com.booksmith.universe.internal.AppearanceForm;
import com.booksmith.universe.internal.CharacterService;
import com.booksmith.universe.internal.CharacterService.CharacterData;
import com.booksmith.universe.internal.UniverseEntity;
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
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Personagens da Bíblia do Universo (§4.3 do produto) e a definição do formulário de aparência (§12.1). */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
class CharacterController {

    private final CharacterService characterService;
    private final GalleryService galleryService;
    private final AppearanceForm appearanceForm;
    private final CurrentUser currentUser;
    private final JsonMapper jsonMapper;

    /** Abas, campos e opções do formulário. O app monta a tela a partir disto. */
    @GetMapping("/forms/character-appearance")
    JsonNode appearanceForm() {
        return appearanceForm.definition();
    }

    @GetMapping("/universes/{universeId}/characters")
    List<CharacterSummary> list(@PathVariable UUID universeId) {
        List<UniverseEntity> characters = characterService.list(currentUser.id(), universeId);
        Map<UUID, String> portraits = galleryService.portraits(universeId);
        return characters.stream().map(c -> summary(c, portraits.get(c.getId()))).toList();
    }

    @PostMapping("/universes/{universeId}/characters")
    @ResponseStatus(HttpStatus.CREATED)
    CharacterResponse create(@PathVariable UUID universeId, @RequestBody @Valid CreateCharacterRequest request) {
        return response(characterService.create(currentUser.id(), universeId, request.name()));
    }

    @GetMapping("/characters/{characterId}")
    CharacterResponse get(@PathVariable UUID characterId) {
        return response(characterService.get(currentUser.id(), characterId));
    }

    /** Salva a ficha inteira; {@code version} é a versão aberta no app (409 se outro aparelho salvou antes). */
    @PutMapping("/characters/{characterId}")
    CharacterResponse update(@PathVariable UUID characterId, @RequestBody @Valid UpdateCharacterRequest request) {
        var data = new CharacterData(request.name(), request.aliases(), request.sheet(), request.appearance());
        return response(characterService.update(currentUser.id(), characterId, request.version(), data));
    }

    @DeleteMapping("/characters/{characterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID characterId) {
        characterService.delete(currentUser.id(), characterId);
    }

    private CharacterSummary summary(UniverseEntity character, String portraitUrl) {
        JsonNode sheet = jsonMapper.readTree(character.getAttributes());
        JsonNode role = sheet.get("role");
        return new CharacterSummary(character.getId(), character.getName(), Arrays.asList(character.getAliases()),
                role == null ? null : role.stringValue(), characterService.appearanceSummary(character),
                portraitUrl, character.getUpdatedAt());
    }

    private CharacterResponse response(UniverseEntity character) {
        return new CharacterResponse(character.getId(), character.getUniverseId(), character.getName(),
                Arrays.asList(character.getAliases()), jsonMapper.readTree(character.getAttributes()),
                jsonMapper.readTree(character.getAppearance()), character.getAppearanceFormVersion(),
                characterService.appearanceSummary(character), character.getPortraitId(),
                galleryService.portrait(character), character.getVersion(), character.getUpdatedAt());
    }

    record CreateCharacterRequest(@NotBlank(message = "Dê um nome ao personagem.") @Size(max = 120) String name) {
    }

    record UpdateCharacterRequest(
            @NotNull Long version,
            @NotBlank(message = "Dê um nome ao personagem.") @Size(max = 120) String name,
            List<String> aliases,
            JsonNode sheet,
            JsonNode appearance) {
    }

    record CharacterSummary(UUID id, String name, List<String> aliases, String role, String appearanceSummary,
                            String portraitUrl, Instant updatedAt) {
    }

    record CharacterResponse(UUID id, UUID universeId, String name, List<String> aliases, JsonNode sheet,
                             JsonNode appearance, int appearanceFormVersion, String appearanceSummary,
                             UUID portraitId, String portraitUrl, long version, Instant updatedAt) {
    }
}
