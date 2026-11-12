package com.booksmith.universe.internal;

import com.booksmith.universe.api.Universes.Brief;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Monta o resumo do universo que vai como contexto das sugestões de IA ({@link Brief}). Quanto mais a IA sabe
 * do que o autor já criou, menos genéricas são as ideias: por isso vão o perfil curto de cada personagem, os
 * lugares com a descrição, as relações, as fichas do mundo e a linha do tempo, tudo cortado em tamanhos que
 * cabem no contexto do modelo local.
 */
@Component
@RequiredArgsConstructor
class UniverseBriefs {

    static final Map<String, String> ROLE_LABELS = Map.of("protagonist", "protagonista",
            "antagonist", "antagonista", "supporting", "secundário", "minor", "figurante");
    /** Como a relação se lê de quem para quem ("Lia é irmã/irmão de Tomás"). */
    static final Map<String, String> RELATION_LABELS = Map.ofEntries(
            Map.entry("parent", "é mãe/pai de"), Map.entry("sibling", "é irmã/irmão de"),
            Map.entry("spouse", "é casada/casado com"), Map.entry("romance", "tem um romance com"),
            Map.entry("friend", "é amiga/amigo de"), Map.entry("mentor", "é mentora/mentor de"),
            Map.entry("rival", "é rival de"), Map.entry("enemy", "é inimiga/inimigo de"),
            Map.entry("ally", "é aliada/aliado de"), Map.entry("serves", "serve a"),
            Map.entry("other", "tem ligação com"));
    static final Map<String, String> PLACE_LABELS = Map.ofEntries(
            Map.entry("city", "cidade"), Map.entry("town", "vila"), Map.entry("neighborhood", "bairro"),
            Map.entry("building", "prédio"), Map.entry("home", "casa"), Map.entry("school", "escola"),
            Map.entry("workplace", "trabalho"), Map.entry("shop", "loja"), Map.entry("nature", "natureza"),
            Map.entry("water", "água"), Map.entry("mountain", "montanha"), Map.entry("realm", "reino"),
            Map.entry("region", "região"), Map.entry("planet", "planeta"), Map.entry("vehicle", "veículo"));

    static final int MAX_CHARACTERS = 30;
    static final int MAX_PLACES = 30;
    static final int MAX_RELATIONS = 40;
    static final int MAX_LORE = 40;
    static final int MAX_EVENTS = 30;
    /** Tamanho de cada trecho (personalidade, descrição...) dentro do resumo. */
    static final int SNIPPET = 160;

    private final UniverseEntityRepository entityRepository;
    private final RelationRepository relationRepository;
    private final TimelineEventRepository timelineRepository;
    private final UniverseForm universeForm;
    private final LoreForm loreForm;
    private final JsonMapper jsonMapper;

    Brief of(Universe universe, JsonNode details, UUID focusId) {
        UUID universeId = universe.getId();
        List<UniverseEntity> people = entityRepository
                .findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(universeId, UniverseEntity.CHARACTER);
        List<UniverseEntity> places = entityRepository
                .findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(universeId, UniverseEntity.PLACE);
        Map<UUID, String> names = new HashMap<>();
        people.forEach(p -> names.put(p.getId(), p.getName()));
        places.forEach(p -> names.put(p.getId(), p.getName()));

        return new Brief(universe.getName(),
                universeForm.describe(details, 600),
                characters(people, focusId),
                places(places),
                relations(universeId, names, focusId),
                lore(universeId),
                timeline(universeId, names));
    }

    /** "Tomás (antagonista) — taverneiro; jeito: desconfiado…; quer: vingar o pai…". O foco fica de fora. */
    private List<String> characters(List<UniverseEntity> people, UUID focusId) {
        List<String> result = new ArrayList<>();
        for (UniverseEntity person : people) {
            if (person.getId().equals(focusId) || result.size() >= MAX_CHARACTERS) {
                continue;
            }
            JsonNode sheet = jsonMapper.readTree(person.getAttributes());
            StringBuilder line = new StringBuilder(person.getName());
            String role = ROLE_LABELS.get(sheet.path("role").stringValue(""));
            if (role != null) {
                line.append(" (").append(role).append(')');
            }
            List<String> parts = new ArrayList<>();
            add(parts, "", sheet.path("occupation").stringValue(""));
            add(parts, "idade: ", sheet.path("age").stringValue(""));
            add(parts, "jeito: ", sheet.path("personality").stringValue(""));
            add(parts, "quer: ", sheet.path("desires").stringValue(""));
            if (!parts.isEmpty()) {
                line.append(" — ").append(String.join("; ", parts));
            }
            result.add(line.toString());
        }
        return result;
    }

    /** "Café do Porto (prédio): onde tudo começa…". */
    private List<String> places(List<UniverseEntity> places) {
        List<String> result = new ArrayList<>();
        for (UniverseEntity place : places.subList(0, Math.min(places.size(), MAX_PLACES))) {
            JsonNode sheet = jsonMapper.readTree(place.getAttributes());
            StringBuilder line = new StringBuilder(place.getName());
            String type = PLACE_LABELS.get(sheet.path("type").stringValue(""));
            if (type != null) {
                line.append(" (").append(type).append(')');
            }
            String description = cut(sheet.path("description").stringValue(""));
            if (description.isEmpty()) {
                description = cut(sheet.path("atmosphere").stringValue(""));
            }
            if (!description.isEmpty()) {
                line.append(": ").append(description);
            }
            String rules = cut(sheet.path("rules").stringValue(""));
            if (!rules.isEmpty()) {
                line.append(" Regras: ").append(rules);
            }
            result.add(line.toString());
        }
        return result;
    }

    /** "Lia é irmã/irmão de Tomás: brigaram depois do incêndio". As do personagem em foco vêm primeiro. */
    private List<String> relations(UUID universeId, Map<UUID, String> names, UUID focusId) {
        List<String> first = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (Relation relation : relationRepository.findAllByUniverseIdAndDeletedAtIsNull(universeId)) {
            String from = names.get(relation.getFromId());
            String to = names.get(relation.getToId());
            if (from == null || to == null) {
                continue;
            }
            StringBuilder line = new StringBuilder(from).append(' ')
                    .append(RELATION_LABELS.getOrDefault(relation.getKind(), "tem ligação com")).append(' ').append(to);
            String description = cut(relation.getDescription() == null ? "" : relation.getDescription());
            if (!description.isEmpty()) {
                line.append(": ").append(description);
            }
            boolean focused = focusId != null
                    && (focusId.equals(relation.getFromId()) || focusId.equals(relation.getToId()));
            (focused ? first : rest).add(line.toString());
        }
        first.addAll(rest);
        return first.subList(0, Math.min(first.size(), MAX_RELATIONS));
    }

    /** "Religiões: A Maré (os mortos voltam com a maré)", agrupado pelo tipo de ficha. */
    private List<String> lore(UUID universeId) {
        Map<String, List<String>> byType = new LinkedHashMap<>();
        int count = 0;
        for (UniverseEntity item : entityRepository
                .findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(universeId, UniverseEntity.LORE)) {
            if (count++ >= MAX_LORE) {
                break;
            }
            String type = jsonMapper.readTree(item.getAttributes()).path("type").stringValue("");
            String label = loreForm.definition().get("types").path(type).path("plural").stringValue("Outras fichas");
            String summaryField = loreForm.summaryField(type);
            String summary = summaryField == null ? ""
                    : cut(jsonMapper.readTree(item.getAppearance()).path(summaryField).stringValue(""));
            byType.computeIfAbsent(label, k -> new ArrayList<>())
                    .add(summary.isEmpty() ? item.getName() : item.getName() + " (" + summary + ")");
        }
        List<String> result = new ArrayList<>();
        byType.forEach((label, items) -> result.add(label + ": " + String.join("; ", items)));
        return result;
    }

    /** "Ano 312 — A grande enchente: a cidade baixa ficou submersa (Lia, Tomás)", em ordem. */
    private List<String> timeline(UUID universeId, Map<UUID, String> names) {
        List<String> result = new ArrayList<>();
        for (TimelineEvent event : timelineRepository.findAllByUniverseIdAndDeletedAtIsNullOrderBySortKeyAsc(universeId)) {
            if (result.size() >= MAX_EVENTS) {
                break;
            }
            StringBuilder line = new StringBuilder();
            String when = event.getWhenLabel() != null && !event.getWhenLabel().isBlank() ? event.getWhenLabel()
                    : event.getWorldDate();
            if (when != null && !when.isBlank()) {
                line.append(when.strip()).append(" — ");
            }
            line.append(event.getTitle());
            String description = cut(event.getDescription() == null ? "" : event.getDescription());
            if (!description.isEmpty()) {
                line.append(": ").append(description);
            }
            List<String> who = new ArrayList<>();
            for (UUID id : event.getCharacterIds()) {
                if (names.containsKey(id)) {
                    who.add(names.get(id));
                }
            }
            if (!who.isEmpty()) {
                line.append(" (").append(String.join(", ", who)).append(')');
            }
            result.add(line.toString());
        }
        return result;
    }

    private static void add(List<String> parts, String prefix, String text) {
        String value = cut(text);
        if (!value.isEmpty()) {
            parts.add(prefix + value);
        }
    }

    /** Uma linha só, cortada em {@link #SNIPPET} caracteres. */
    static String cut(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return flat.length() > SNIPPET ? flat.substring(0, SNIPPET - 1) + "…" : flat;
    }
}
