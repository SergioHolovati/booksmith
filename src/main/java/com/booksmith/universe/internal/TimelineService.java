package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Linha do tempo básica: eventos na ordem do autor. A posição é um {@code sort_key} decimal: inserir ou
 * mover um evento usa o ponto médio entre os vizinhos, sem renumerar os outros. Quando as casas decimais
 * crescem demais (muitas trocas no mesmo lugar), a ordem inteira é renumerada de uma vez.
 */
@Service
@RequiredArgsConstructor
public class TimelineService {

    static final BigDecimal STEP = BigDecimal.valueOf(1_000);
    static final int MAX_SCALE = 18;
    static final int MAX_EVENTS = 2_000;
    static final int MAX_CHARACTERS_PER_EVENT = 50;
    static final int MAX_LORE_PER_EVENT = 20;
    /** Tipos de acontecimento: o app mostra ícone e rótulo; "birth" alimenta o cálculo de idade. */
    static final Set<String> KINDS = Set.of("birth", "death", "war", "battle", "founding", "coronation", "treaty",
            "catastrophe", "discovery", "journey", "wedding");

    private final UniverseRepository universeRepository;
    private final UniverseEntityRepository entityRepository;
    private final TimelineEventRepository eventRepository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<TimelineEvent> list(UUID ownerId, UUID universeId) {
        requireUniverse(ownerId, universeId);
        return events(universeId);
    }

    /** Cria o evento logo depois de {@code afterId} ou, sem ele, no fim da linha do tempo. */
    @Transactional
    public TimelineEvent create(UUID ownerId, UUID universeId, String title, UUID afterId) {
        requireUniverse(ownerId, universeId);
        List<TimelineEvent> events = events(universeId);
        if (events.size() >= MAX_EVENTS) {
            throw ApiException.unprocessable("too_many_events",
                    "Esta linha do tempo chegou a %d eventos.".formatted(MAX_EVENTS));
        }
        BigDecimal key = afterId == null && !events.isEmpty()
                ? events.getLast().getSortKey().add(STEP)
                : keyAfter(events, afterId);
        return eventRepository.save(TimelineEvent.builder()
                .universeId(universeId)
                .title(text(title, "title", 200, true))
                .sortKey(key)
                .build());
    }

    @Transactional
    public TimelineEvent update(UUID ownerId, UUID eventId, long expectedVersion, EventData data) {
        TimelineEvent event = require(ownerId, eventId);
        if (event.getVersion() != expectedVersion) {
            throw ApiException.conflict("event_version_conflict", "Este evento foi alterado em outro aparelho.",
                    Map.of("currentVersion", event.getVersion()));
        }
        event.setTitle(text(data.title(), "title", 200, true));
        event.setWhenLabel(text(data.whenLabel(), "whenLabel", 100, false));
        event.setDescription(text(data.description(), "description", 4_000, false));
        event.setChapterId(data.chapterId());
        event.setPlaceId(checkPlace(event.getUniverseId(), data.placeId()));
        event.setCharacterIds(checkCharacters(event.getUniverseId(), data.characterIds()));
        if (data.kind() != null && !data.kind().isBlank() && !KINDS.contains(data.kind())) {
            throw invalid("tipo \"%s\" não existe".formatted(data.kind()));
        }
        event.setKind(data.kind() == null || data.kind().isBlank() ? null : data.kind());
        WorldCalendar.WorldDate date = calendar(event.getUniverseId()).parseDate(data.date());
        event.setWorldDate(date == null ? null : jsonMapper.writeValueAsString(WorldCalendar.dateJson(date, jsonMapper)));
        event.setLoreIds(checkLore(event.getUniverseId(), data.loreIds()));
        return eventRepository.saveAndFlush(event);
    }

    /** Move o evento para logo depois de {@code afterId}; sem ele, para o começo. */
    @Transactional
    public TimelineEvent move(UUID ownerId, UUID eventId, UUID afterId) {
        TimelineEvent event = require(ownerId, eventId);
        if (eventId.equals(afterId)) {
            throw invalid("um evento não pode ir para depois dele mesmo");
        }
        List<TimelineEvent> others = new ArrayList<>(events(event.getUniverseId()));
        others.removeIf(e -> e.getId().equals(eventId));
        event.setSortKey(keyAfter(others, afterId));
        if (event.getSortKey().scale() > MAX_SCALE) {
            renumber(others, event, afterId);
        }
        return eventRepository.saveAndFlush(event);
    }

    /**
     * Põe os eventos com data em ordem cronológica. Os sem data (ou com data numa era que não existe mais) ficam
     * onde estavam; os datados ocupam as próprias posições, agora em ordem. Empate mantém a ordem de antes.
     */
    @Transactional
    public void sortByDate(UUID ownerId, UUID universeId) {
        requireUniverse(ownerId, universeId);
        WorldCalendar calendar = calendar(universeId);
        List<TimelineEvent> events = new ArrayList<>(events(universeId));
        List<Integer> slots = new ArrayList<>();
        List<TimelineEvent> dated = new ArrayList<>();
        Map<UUID, Long> day = new java.util.HashMap<>();
        for (int i = 0; i < events.size(); i++) {
            WorldCalendar.WorldDate date = dateOf(events.get(i));
            Long absolute = date == null ? null : calendar.absoluteDay(date);
            if (absolute != null) {
                slots.add(i);
                dated.add(events.get(i));
                day.put(events.get(i).getId(), absolute);
            }
        }
        dated.sort(java.util.Comparator.comparing(e -> day.get(e.getId())));
        for (int k = 0; k < slots.size(); k++) {
            events.set(slots.get(k), dated.get(k));
        }
        for (int i = 0; i < events.size(); i++) {
            events.get(i).setSortKey(STEP.multiply(BigDecimal.valueOf(i + 1L)));
        }
        eventRepository.flush();
    }

    // ===== Calendário e idades =====

    @Transactional(readOnly = true)
    public WorldCalendar calendar(UUID universeId) {
        String json = universeRepository.findById(universeId).map(Universe::getCalendar).orElse(null);
        return json == null ? WorldCalendar.DEFAULT : WorldCalendar.parse(jsonMapper.readTree(json));
    }

    @Transactional(readOnly = true)
    public WorldCalendar calendar(UUID ownerId, UUID universeId) {
        requireUniverse(ownerId, universeId);
        return calendar(universeId);
    }

    /**
     * Troca o calendário. Datas que ficariam fora dele (mês que deixou de existir, dia além do novo tamanho do
     * mês) continuam guardadas como estão; o app mostra o que conseguir e o autor ajusta.
     */
    @Transactional
    public WorldCalendar setCalendar(UUID ownerId, UUID universeId, JsonNode calendar) {
        Universe universe = universeRepository.findByIdAndOwnerId(universeId, ownerId)
                .orElseThrow(() -> ApiException.notFound("universe_not_found", "Universo não encontrado."));
        WorldCalendar parsed = WorldCalendar.parse(calendar);
        universe.setCalendar(jsonMapper.writeValueAsString(parsed.toJson(jsonMapper)));
        return parsed;
    }

    public WorldCalendar.WorldDate dateOf(TimelineEvent event) {
        if (event.getWorldDate() == null) {
            return null;
        }
        JsonNode node = jsonMapper.readTree(event.getWorldDate());
        return new WorldCalendar.WorldDate(node.path("era").asString(""), node.path("year").asInt(),
                node.path("month").isIntegralNumber() ? node.path("month").intValue() : null,
                node.path("day").isIntegralNumber() ? node.path("day").intValue() : null);
    }

    /** Data de nascimento de cada personagem: o primeiro evento "birth" datado em que ele aparece. */
    public Map<UUID, WorldCalendar.WorldDate> births(List<TimelineEvent> events) {
        Map<UUID, WorldCalendar.WorldDate> births = new java.util.HashMap<>();
        for (TimelineEvent event : events) {
            WorldCalendar.WorldDate date = dateOf(event);
            if ("birth".equals(event.getKind()) && date != null) {
                for (UUID character : event.getCharacterIds()) {
                    births.putIfAbsent(character, date);
                }
            }
        }
        return births;
    }

    @Transactional
    public void delete(UUID ownerId, UUID eventId) {
        require(ownerId, eventId).setDeletedAt(Instant.now(clock));
    }

    /** Nomes dos personagens e lugares do universo, para mostrar os vínculos sem uma consulta por evento. */
    @Transactional(readOnly = true)
    public Map<UUID, String> entityNames(UUID universeId) {
        Map<UUID, String> names = new java.util.HashMap<>();
        for (String kind : List.of(UniverseEntity.CHARACTER, UniverseEntity.PLACE, UniverseEntity.LORE)) {
            entityRepository.findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(universeId, kind)
                    .forEach(e -> names.put(e.getId(), e.getName()));
        }
        return names;
    }

    // ===== Ordem =====

    /** Chave para ficar logo depois de {@code afterId} (ou no começo, se nulo) na lista já ordenada. */
    static BigDecimal keyAfter(List<TimelineEvent> ordered, UUID afterId) {
        if (afterId == null) {
            return ordered.isEmpty() ? STEP : ordered.getFirst().getSortKey().subtract(STEP);
        }
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).getId().equals(afterId)) {
                BigDecimal before = ordered.get(i).getSortKey();
                if (i == ordered.size() - 1) {
                    return before.add(STEP);
                }
                BigDecimal after = ordered.get(i + 1).getSortKey();
                return before.add(after).divide(BigDecimal.TWO, before.scale() + after.scale() + 1, RoundingMode.HALF_UP)
                        .stripTrailingZeros();
            }
        }
        throw invalid("o evento de referência não está nesta linha do tempo");
    }

    private void renumber(List<TimelineEvent> others, TimelineEvent moved, UUID afterId) {
        List<TimelineEvent> ordered = new ArrayList<>(others);
        int index = afterId == null ? 0 : ordered.indexOf(ordered.stream()
                .filter(e -> e.getId().equals(afterId)).findFirst().orElseThrow()) + 1;
        ordered.add(index, moved);
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setSortKey(STEP.multiply(BigDecimal.valueOf(i + 1L)));
        }
    }

    // ===== Validação =====

    private UUID checkPlace(UUID universeId, UUID placeId) {
        if (placeId == null) {
            return null;
        }
        boolean ok = entityRepository.findByIdAndKindAndDeletedAtIsNull(placeId, UniverseEntity.PLACE)
                .filter(p -> p.getUniverseId().equals(universeId)).isPresent();
        if (!ok) {
            throw invalid("o lugar precisa ser deste universo");
        }
        return placeId;
    }

    private UUID[] checkCharacters(UUID universeId, List<UUID> characterIds) {
        if (characterIds == null || characterIds.isEmpty()) {
            return new UUID[0];
        }
        Set<UUID> unique = new LinkedHashSet<>(characterIds);
        if (unique.size() > MAX_CHARACTERS_PER_EVENT) {
            throw invalid("no máximo %d personagens por evento".formatted(MAX_CHARACTERS_PER_EVENT));
        }
        for (UUID id : unique) {
            boolean ok = entityRepository.findByIdAndKindAndDeletedAtIsNull(id, UniverseEntity.CHARACTER)
                    .filter(c -> c.getUniverseId().equals(universeId)).isPresent();
            if (!ok) {
                throw invalid("os personagens precisam ser deste universo");
            }
        }
        return unique.toArray(UUID[]::new);
    }

    private UUID[] checkLore(UUID universeId, List<UUID> loreIds) {
        if (loreIds == null || loreIds.isEmpty()) {
            return new UUID[0];
        }
        Set<UUID> unique = new LinkedHashSet<>(loreIds);
        if (unique.size() > MAX_LORE_PER_EVENT) {
            throw invalid("no máximo %d fichas do mundo por evento".formatted(MAX_LORE_PER_EVENT));
        }
        for (UUID id : unique) {
            boolean ok = entityRepository.findByIdAndKindAndDeletedAtIsNull(id, UniverseEntity.LORE)
                    .filter(l -> l.getUniverseId().equals(universeId)).isPresent();
            if (!ok) {
                throw invalid("as fichas do mundo precisam ser deste universo");
            }
        }
        return unique.toArray(UUID[]::new);
    }

    private static String text(String value, String field, int limit, boolean required) {
        String text = value == null ? "" : value.strip();
        if (required && text.isEmpty()) {
            throw invalid("dê um título ao evento");
        }
        if (text.length() > limit) {
            throw invalid("\"%s\" passou de %d caracteres".formatted(field, limit));
        }
        return text.isEmpty() ? null : text;
    }

    private List<TimelineEvent> events(UUID universeId) {
        return eventRepository.findAllByUniverseIdAndDeletedAtIsNullOrderBySortKeyAsc(universeId);
    }

    private void requireUniverse(UUID ownerId, UUID universeId) {
        universeRepository.findByIdAndOwnerId(universeId, ownerId)
                .orElseThrow(() -> ApiException.notFound("universe_not_found", "Universo não encontrado."));
    }

    private TimelineEvent require(UUID ownerId, UUID eventId) {
        TimelineEvent event = eventRepository.findByIdAndDeletedAtIsNull(eventId).orElseThrow(TimelineService::notFound);
        if (universeRepository.findByIdAndOwnerId(event.getUniverseId(), ownerId).isEmpty()) {
            throw notFound();
        }
        return event;
    }

    private static ApiException notFound() {
        return ApiException.notFound("event_not_found", "Evento não encontrado.");
    }

    private static ApiException invalid(String reason) {
        return ApiException.unprocessable("event_invalid", "Evento inválido: " + reason + ".");
    }

    /** {@code date}: objeto {era, year, month?, day?} conferido contra o calendário do universo. */
    public record EventData(String title, String whenLabel, String description, UUID chapterId, UUID placeId,
                            List<UUID> characterIds, String kind, JsonNode date, List<UUID> loreIds) {
    }
}
