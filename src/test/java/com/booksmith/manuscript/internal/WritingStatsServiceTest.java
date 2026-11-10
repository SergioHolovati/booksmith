package com.booksmith.manuscript.internal;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WritingStatsServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    @Test
    void streakCountsDaysInARowThatReachedTheGoal() {
        var words = Map.of(TODAY, 600, TODAY.minusDays(1), 500, TODAY.minusDays(2), 800, TODAY.minusDays(3), 100,
                TODAY.minusDays(4), 900);
        assertThat(WritingStatsService.streak(words, TODAY, 500)).isEqualTo(3); // o dia 4 tinha só 100
    }

    @Test
    void todayNotDoneYetKeepsYesterdaysStreakAlive() {
        var words = Map.of(TODAY, 120, TODAY.minusDays(1), 700, TODAY.minusDays(2), 700);
        assertThat(WritingStatsService.streak(words, TODAY, 500)).isEqualTo(2);
        assertThat(WritingStatsService.streak(Map.of(TODAY.minusDays(2), 700), TODAY, 500)).isZero();
    }

    @Test
    void withoutAGoalAnyWritingCounts() {
        var words = Map.of(TODAY, 3, TODAY.minusDays(1), 1, TODAY.minusDays(2), -40);
        assertThat(WritingStatsService.streak(words, TODAY, null)).isEqualTo(2); // apagar mais do que escreveu não conta
    }

    @Test
    void timeZoneComesFromTheDeviceOrFallsBack() {
        assertThat(WritingStatsService.zone("Europe/Lisbon")).isEqualTo(ZoneId.of("Europe/Lisbon"));
        assertThat(WritingStatsService.zone("Marte/Base")).isEqualTo(WritingStatsService.DEFAULT_ZONE);
        assertThat(WritingStatsService.zone(null)).isEqualTo(WritingStatsService.DEFAULT_ZONE);
    }
}
