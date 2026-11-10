package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Calendário do mundo: eras em ordem (cada uma conta os anos a partir de 1; todas menos a última têm duração),
 * meses com nome e número de dias, e dias da semana. Ficção tem calendários próprios ("ano 312 da Era das
 * Cinzas"); sem um, vale o padrão, o nosso sem anos bissextos.
 * <p>
 * Uma data pode ter só o ano, ano e mês, ou dia, mês e ano. Para comparar datas e calcular idades, cada data
 * vira um número de dia contado desde o começo da primeira era.
 */
public record WorldCalendar(List<Era> eras, List<Month> months, List<String> weekdays) {

    static final int MAX_ERAS = 20;
    static final int MAX_MONTHS = 24;
    static final int MAX_MONTH_DAYS = 100;
    static final int MAX_WEEKDAYS = 14;
    static final int MAX_NAME = 60;
    static final int MAX_YEAR = 1_000_000;

    /** Uma era; {@code years} é a duração (nula só na última, a atual). */
    public record Era(String key, String name, String abbreviation, Integer years) {
    }

    public record Month(String name, int days) {
    }

    /** Data no calendário do mundo: ano obrigatório; mês e dia, opcionais. */
    public record WorldDate(String era, int year, Integer month, Integer day) {
    }

    public static final WorldCalendar DEFAULT = new WorldCalendar(
            List.of(new Era("e1", "", "", null)),
            List.of(new Month("janeiro", 31), new Month("fevereiro", 28), new Month("março", 31),
                    new Month("abril", 30), new Month("maio", 31), new Month("junho", 30), new Month("julho", 31),
                    new Month("agosto", 31), new Month("setembro", 30), new Month("outubro", 31),
                    new Month("novembro", 30), new Month("dezembro", 31)),
            List.of("domingo", "segunda", "terça", "quarta", "quinta", "sexta", "sábado"));

    public int daysPerYear() {
        return months.stream().mapToInt(Month::days).sum();
    }

    // ===== Leitura e validação =====

    /** Confere e limpa o calendário enviado pelo app; eras sem chave ganham uma ("e1", "e2"…). */
    public static WorldCalendar parse(JsonNode node) {
        if (node == null || node.isNull()) {
            return DEFAULT;
        }
        if (!node.isObject()) {
            throw invalid("envie um objeto com eras, meses e dias da semana");
        }
        List<Era> eras = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        JsonNode erasNode = node.path("eras");
        if (!erasNode.isArray() || erasNode.isEmpty() || erasNode.size() > MAX_ERAS) {
            throw invalid("de 1 a %d eras".formatted(MAX_ERAS));
        }
        for (int i = 0; i < erasNode.size(); i++) {
            JsonNode era = erasNode.get(i);
            boolean last = i == erasNode.size() - 1;
            String key = era.path("key").asString("");
            if (!key.matches("[A-Za-z0-9_-]{1,20}") || !keys.add(key)) {
                key = "e" + (i + 1);
                while (!keys.add(key)) {
                    key = key + "x";
                }
            }
            Integer years = era.path("years").isIntegralNumber() ? era.path("years").intValue() : null;
            if (!last && (years == null || years < 1 || years > MAX_YEAR)) {
                throw invalid("a era \"%s\" precisa de duração em anos (só a última pode ficar aberta)"
                        .formatted(text(era, "name", "sem nome")));
            }
            eras.add(new Era(key, text(era, "name", ""), text(era, "abbreviation", ""), last ? null : years));
        }
        List<Month> months = new ArrayList<>();
        JsonNode monthsNode = node.path("months");
        if (!monthsNode.isArray() || monthsNode.isEmpty() || monthsNode.size() > MAX_MONTHS) {
            throw invalid("de 1 a %d meses".formatted(MAX_MONTHS));
        }
        for (JsonNode month : monthsNode) {
            int days = month.path("days").asInt(0);
            String name = text(month, "name", "");
            if (name.isEmpty() || days < 1 || days > MAX_MONTH_DAYS) {
                throw invalid("cada mês precisa de nome e de 1 a %d dias".formatted(MAX_MONTH_DAYS));
            }
            months.add(new Month(name, days));
        }
        List<String> weekdays = new ArrayList<>();
        JsonNode weekNode = node.path("weekdays");
        if (weekNode.isArray()) {
            if (weekNode.size() > MAX_WEEKDAYS) {
                throw invalid("no máximo %d dias da semana".formatted(MAX_WEEKDAYS));
            }
            for (JsonNode day : weekNode) {
                String name = day.asString("").strip();
                if (!name.isEmpty()) {
                    weekdays.add(limit(name));
                }
            }
        }
        return new WorldCalendar(List.copyOf(eras), List.copyOf(months), List.copyOf(weekdays));
    }

    public ObjectNode toJson(JsonMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        ArrayNode erasNode = node.putArray("eras");
        for (Era era : eras) {
            ObjectNode e = erasNode.addObject().put("key", era.key()).put("name", era.name())
                    .put("abbreviation", era.abbreviation());
            if (era.years() == null) {
                e.putNull("years");
            } else {
                e.put("years", era.years());
            }
        }
        ArrayNode monthsNode = node.putArray("months");
        months.forEach(m -> monthsNode.addObject().put("name", m.name()).put("days", m.days()));
        ArrayNode week = node.putArray("weekdays");
        weekdays.forEach(week::add);
        return node;
    }

    /** Confere uma data contra o calendário; nula ou vazia → nula. */
    public WorldDate parseDate(JsonNode node) {
        if (node == null || node.isNull() || node.isEmpty()) {
            return null;
        }
        if (!node.path("year").isIntegralNumber()) {
            throw invalidDate("informe o ano");
        }
        int year = node.path("year").intValue();
        String era = node.path("era").asString(eras.getFirst().key());
        Era found = eras.stream().filter(e -> e.key().equals(era)).findFirst()
                .orElseThrow(() -> invalidDate("era desconhecida"));
        if (year < 1 || year > MAX_YEAR || (found.years() != null && year > found.years())) {
            throw invalidDate(found.years() == null ? "ano de 1 a %d".formatted(MAX_YEAR)
                    : "nesta era, o ano vai de 1 a %d".formatted(found.years()));
        }
        Integer month = node.path("month").isIntegralNumber() ? node.path("month").intValue() : null;
        Integer day = node.path("day").isIntegralNumber() ? node.path("day").intValue() : null;
        if (month != null && (month < 1 || month > months.size())) {
            throw invalidDate("mês de 1 a %d".formatted(months.size()));
        }
        if (day != null && month == null) {
            throw invalidDate("para o dia, informe também o mês");
        }
        if (day != null && (day < 1 || day > months.get(month - 1).days())) {
            throw invalidDate("%s tem %d dias".formatted(months.get(month - 1).name(), months.get(month - 1).days()));
        }
        return new WorldDate(era, year, month, day);
    }

    public static ObjectNode dateJson(WorldDate date, JsonMapper mapper) {
        ObjectNode node = mapper.createObjectNode().put("era", date.era()).put("year", date.year());
        if (date.month() != null) {
            node.put("month", date.month());
        }
        if (date.day() != null) {
            node.put("day", date.day());
        }
        return node;
    }

    // ===== Contas =====

    /** Ano contado desde o começo da primeira era; nulo se a era da data não existe mais. */
    public Long absoluteYear(WorldDate date) {
        long before = 0;
        for (Era era : eras) {
            if (era.key().equals(date.era())) {
                return before + date.year();
            }
            before += era.years() == null ? 0 : era.years();
        }
        return null;
    }

    /** Dia contado desde o começo; data sem mês conta como o primeiro dia do ano (para ordenar). */
    public Long absoluteDay(WorldDate date) {
        Long year = absoluteYear(date);
        if (year == null) {
            return null;
        }
        long day = (year - 1) * daysPerYear();
        if (date.month() != null) {
            for (int m = 0; m < date.month() - 1 && m < months.size(); m++) {
                day += months.get(m).days();
            }
            day += date.day() == null ? 0 : date.day() - 1;
        }
        return day;
    }

    /**
     * Idade completa em {@code at} de quem nasceu em {@code birth}; nula se não dá para saber (era removida,
     * ou data anterior ao nascimento). Sem mês numa das datas, conta só os anos.
     */
    public Integer age(WorldDate birth, WorldDate at) {
        Long born = absoluteYear(birth);
        Long now = absoluteYear(at);
        if (born == null || now == null) {
            return null;
        }
        long years = now - born;
        if (birth.month() != null && at.month() != null) {
            int b = birth.month() * 1000 + (birth.day() == null ? 0 : birth.day());
            int a = at.month() * 1000 + (at.day() == null ? 0 : at.day());
            if (a < b) {
                years--;
            }
        }
        return years < 0 ? null : (int) years;
    }

    /** "12 de brumal de 312 E.C.", "brumal de 312", "ano 312 E.C."; no calendário padrão, "12 de março de 1999". */
    public String label(WorldDate date) {
        Era era = eras.stream().filter(e -> e.key().equals(date.era())).findFirst().orElse(null);
        String suffix = era == null || era.abbreviation().isEmpty() ? "" : " " + era.abbreviation();
        StringBuilder text = new StringBuilder();
        if (date.month() != null && date.month() <= months.size()) {
            if (date.day() != null) {
                text.append(date.day()).append(" de ");
            }
            text.append(months.get(date.month() - 1).name()).append(" de ").append(date.year());
        } else {
            text.append(era != null && era.abbreviation().isEmpty() && era.name().isEmpty() ? "" : "ano ").append(date.year());
        }
        return text + suffix;
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asString(fallback).strip();
        return limit(value);
    }

    private static String limit(String value) {
        return value.length() > MAX_NAME ? value.substring(0, MAX_NAME).strip() : value;
    }

    private static ApiException invalid(String reason) {
        return ApiException.unprocessable("calendar_invalid", "Calendário inválido: " + reason + ".");
    }

    private static ApiException invalidDate(String reason) {
        return ApiException.unprocessable("event_invalid", "Data inválida: " + reason + ".");
    }
}
