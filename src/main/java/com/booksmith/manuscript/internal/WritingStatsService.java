package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Meta diária de palavras e sequência de dias (§4.1 do produto). Cada salvamento automático soma ao dia o saldo
 * de palavras (escritas menos apagadas), no fuso de quem escreve. A sequência conta os dias seguidos em que a meta
 * foi batida (sem meta, qualquer escrita conta) e continua viva até o fim do dia de hoje.
 */
@Service
@RequiredArgsConstructor
public class WritingStatsService {

    public static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Sao_Paulo");
    static final int MIN_GOAL = 50;
    static final int MAX_GOAL = 20_000;
    static final int HISTORY_DAYS = 30;
    /** Até onde olhar para trás na sequência: mais de um ano seguido já é história para contar. */
    static final int MAX_STREAK_LOOKBACK = 400;

    private final JdbcClient jdbc;
    private final Clock clock;

    /** "America/Sao_Paulo" do aparelho; inválido ou ausente vira o fuso padrão. */
    public static ZoneId zone(String header) {
        if (header == null || header.isBlank() || header.length() > 64) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(header.strip());
        } catch (DateTimeException e) {
            return DEFAULT_ZONE;
        }
    }

    /** Soma o saldo do salvamento ao dia de hoje (no fuso de quem escreve). */
    @Transactional
    public void record(UUID ownerId, int delta, ZoneId zone) {
        if (delta == 0) {
            return;
        }
        jdbc.sql("""
                        insert into manuscript.writing_days (owner_id, day, words) values (:owner, :day, :words)
                        on conflict (owner_id, day) do update
                        set words = manuscript.writing_days.words + excluded.words, updated_at = now()
                        """)
                .param("owner", ownerId)
                .param("day", today(zone))
                .param("words", delta)
                .update();
    }

    @Transactional(readOnly = true)
    public Stats stats(UUID ownerId, ZoneId zone) {
        LocalDate today = today(zone);
        Integer goal = goal(ownerId);
        Map<LocalDate, Integer> words = new HashMap<>();
        jdbc.sql("select day, words from manuscript.writing_days where owner_id = :owner and day > :since")
                .param("owner", ownerId)
                .param("since", today.minusDays(MAX_STREAK_LOOKBACK))
                .query((rs, n) -> Map.entry(rs.getObject("day", LocalDate.class), rs.getInt("words")))
                .list()
                .forEach(e -> words.put(e.getKey(), e.getValue()));

        List<Day> history = new ArrayList<>();
        for (int i = HISTORY_DAYS - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            history.add(new Day(day, Math.max(0, words.getOrDefault(day, 0))));
        }
        int todayWords = Math.max(0, words.getOrDefault(today, 0));
        return new Stats(todayWords, goal, streak(words, today, goal), goal != null && todayWords >= goal, history);
    }

    /** {@code dailyWords} null tira a meta. */
    @Transactional
    public Integer setGoal(UUID ownerId, Integer dailyWords) {
        if (dailyWords != null && (dailyWords < MIN_GOAL || dailyWords > MAX_GOAL)) {
            throw ApiException.unprocessable("goal_invalid",
                    "A meta precisa ficar entre %d e %d palavras por dia.".formatted(MIN_GOAL, MAX_GOAL));
        }
        jdbc.sql("""
                        insert into manuscript.writing_goals (owner_id, daily_words) values (:owner, :goal)
                        on conflict (owner_id) do update set daily_words = excluded.daily_words, updated_at = now()
                        """)
                .param("owner", ownerId)
                .param("goal", dailyWords)
                .update();
        return dailyWords;
    }

    /**
     * Dias seguidos com a meta batida (ou com escrita, sem meta), terminando hoje. Se hoje ainda não bateu, a
     * sequência de ontem continua valendo: ela só quebra quando o dia acaba.
     */
    static int streak(Map<LocalDate, Integer> words, LocalDate today, Integer goal) {
        int threshold = goal == null ? 1 : goal;
        LocalDate day = words.getOrDefault(today, 0) >= threshold ? today : today.minusDays(1);
        int count = 0;
        while (count < MAX_STREAK_LOOKBACK && words.getOrDefault(day, 0) >= threshold) {
            count++;
            day = day.minusDays(1);
        }
        return count;
    }

    private Integer goal(UUID ownerId) {
        return jdbc.sql("select daily_words from manuscript.writing_goals where owner_id = :owner")
                .param("owner", ownerId)
                .query((rs, n) -> (Integer) rs.getObject("daily_words"))
                .optional()
                .orElse(null);
    }

    private LocalDate today(ZoneId zone) {
        return LocalDate.now(clock.withZone(zone));
    }

    /** {@code words}: saldo do dia, nunca negativo na tela. */
    public record Day(LocalDate day, int words) {
    }

    /** {@code goalReached}: hoje já bateu a meta. {@code history}: os últimos 30 dias, do mais antigo a hoje. */
    public record Stats(int today, Integer goal, int streak, boolean goalReached, List<Day> history) {
    }
}
