package com.booksmith.manuscript.internal;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ChapterHistoryTest {

    private static final Instant NOW = Instant.parse("2026-11-06T20:00:00Z");

    private static ChapterVersion lastAt(Instant createdAt, int words) {
        return ChapterVersion.builder().createdAt(createdAt).wordCount(words).build();
    }

    @Test
    void emptyChapterHasNothingToPreserve() {
        assertThat(ChapterHistory.isDue(0, null, 1000, NOW)).isFalse();
    }

    @Test
    void firstTextEverIsPreserved() {
        assertThat(ChapterHistory.isDue(120, null, 125, NOW)).isTrue();
    }

    @Test
    void autosavesCloseInTimeDoNotCreateVersions() {
        var last = lastAt(NOW.minusSeconds(9 * 60), 1000);
        assertThat(ChapterHistory.isDue(1010, last, 1020, NOW)).isFalse();
    }

    @Test
    void tenMinutesAfterTheLastVersionCreatesANewOne() {
        var last = lastAt(NOW.minus(ChapterHistory.MIN_INTERVAL), 1000);
        assertThat(ChapterHistory.isDue(1010, last, 1020, NOW)).isTrue();
    }

    @Test
    void bigChangeCreatesAVersionEvenSoon() {
        // Ex.: apagou um trecho grande ou colou outro texto por cima
        var last = lastAt(NOW.minusSeconds(60), 1000);
        assertThat(ChapterHistory.isDue(1000, last, 400, NOW)).isTrue();
        assertThat(ChapterHistory.isDue(1000, last, 1000 + ChapterHistory.MIN_WORD_CHANGE, NOW)).isTrue();
    }
}
