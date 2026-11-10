package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.universe.internal.WorldCalendar.WorldDate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldCalendarTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    /** Duas eras: a Antiga, de 500 anos, e a das Cinzas (atual); ano de 3 meses curtos. */
    private final WorldCalendar vel = WorldCalendar.parse(mapper.readTree("""
            {"eras": [{"key": "antiga", "name": "Era Antiga", "abbreviation": "E.A.", "years": 500},
                      {"name": "Era das Cinzas", "abbreviation": "E.C."}],
             "months": [{"name": "Brumal", "days": 40}, {"name": "Floral", "days": 40}, {"name": "Solar", "days": 40}],
             "weekdays": ["Lun", "Mar", " "]}"""));

    @Test
    void erasWithoutKeyGetOneAndOnlyTheLastIsOpen() {
        assertThat(vel.eras()).extracting(WorldCalendar.Era::key).containsExactly("antiga", "e2");
        assertThat(vel.eras().getLast().years()).isNull();
        assertThat(vel.weekdays()).containsExactly("Lun", "Mar");
        assertThat(vel.daysPerYear()).isEqualTo(120);
        assertThatThrownBy(() -> WorldCalendar.parse(mapper.readTree(
                "{\"eras\": [{\"name\": \"A\"}, {\"name\": \"B\"}], \"months\": [{\"name\": \"M\", \"days\": 30}]}")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("calendar_invalid"));
    }

    @Test
    void datesAreCheckedAgainstTheCalendar() {
        assertThat(vel.parseDate(mapper.readTree("{\"era\": \"e2\", \"year\": 312, \"month\": 1, \"day\": 12}")))
                .isEqualTo(new WorldDate("e2", 312, 1, 12));
        assertInvalidDate("{\"era\": \"antiga\", \"year\": 501}");
        assertInvalidDate("{\"era\": \"e2\", \"year\": 3, \"month\": 4}");
        assertInvalidDate("{\"era\": \"e2\", \"year\": 3, \"month\": 1, \"day\": 41}");
        assertInvalidDate("{\"era\": \"e2\", \"year\": 3, \"day\": 2}");
        assertInvalidDate("{\"era\": \"nenhuma\", \"year\": 3}");
        assertThat(vel.parseDate(mapper.readTree("{}"))).isNull();
    }

    @Test
    void agesCrossErasAndCountMonthsAndDays() {
        WorldDate birth = new WorldDate("antiga", 490, 2, 10);
        assertThat(vel.age(birth, new WorldDate("e2", 12, 2, 9))).isEqualTo(21);
        assertThat(vel.age(birth, new WorldDate("e2", 12, 2, 10))).isEqualTo(22);
        assertThat(vel.age(birth, new WorldDate("e2", 12, null, null))).isEqualTo(22);
        assertThat(vel.age(birth, new WorldDate("antiga", 480, null, null))).isNull();
        assertThat(vel.absoluteDay(new WorldDate("e2", 1, 1, 1))).isEqualTo(500L * 120);
    }

    @Test
    void labelsReadLikeDates() {
        assertThat(vel.label(new WorldDate("e2", 312, 1, 12))).isEqualTo("12 de Brumal de 312 E.C.");
        assertThat(vel.label(new WorldDate("e2", 312, 2, null))).isEqualTo("Floral de 312 E.C.");
        assertThat(vel.label(new WorldDate("antiga", 40, null, null))).isEqualTo("ano 40 E.A.");
        assertThat(WorldCalendar.DEFAULT.label(new WorldDate("e1", 1999, 3, 12))).isEqualTo("12 de março de 1999");
        assertThat(WorldCalendar.DEFAULT.label(new WorldDate("e1", 1999, null, null))).isEqualTo("1999");
    }

    private void assertInvalidDate(String json) {
        assertThatThrownBy(() -> vel.parseDate(mapper.readTree(json)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("event_invalid"));
    }
}
