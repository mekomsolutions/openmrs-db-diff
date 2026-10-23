package net.mekomsolutions.dbdiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.mekomsolutions.dbdiff.TimeShift.Change;
import net.mekomsolutions.dbdiff.TimeShift.Window;

class TimeShiftTest {

    private static final ZoneId HAITI = ZoneId.of("America/Port-au-Prince");
    private static final Set<String> SKIPPED = Set.of("obs.value_datetime");

    private final TimeShift shift = new TimeShift(HAITI, SKIPPED);
    private final Window window = new Window(TimeShift.parseInput("2021-02-04"), TimeShift.parseInput("2023-09-26"));

    @Test
    void followsTheZonesDaylightSavingTimeRules() {
        // UTC-5 in winter, UTC-4 in summer from 2017 on; Haiti kept UTC-5 all of 2016.
        assertEquals("2022-01-10 10:00:00", local("2022-01-10 15:00:00"));
        assertEquals("2022-07-01 11:00:00", local("2022-07-01 15:00:00"));
        assertEquals("2016-07-01 10:00:00", local("2016-07-01 15:00:00"));
        assertEquals("2022-05-01 00:00:00", local("2022-05-01 04:00:00"));
    }

    @Test
    void canGoFromLocalTimeToUtc() {
        TimeShift toUtc = new TimeShift(HAITI, SKIPPED, true);

        assertEquals("2025-01-10 14:00:00", TimeShift.format(toUtc.toUtc(TimeShift.parse("2025-01-10 09:00:00"))));
        assertEquals("2025-07-10 13:00:00", TimeShift.format(toUtc.toUtc(TimeShift.parse("2025-07-10 09:00:00"))));
        assertEquals(List.of(new Change("date_created", "2025-07-10 09:00:00", "2025-07-10 13:00:00")),
                toUtc.changes(table("person", "date_created"), row("date_created", "2025-07-10 09:00:00"),
                        new Window(null, TimeShift.parseInput("2026-08-28"))));
    }

    @Test
    void shiftsEveryDatetimeOfARowCreatedInTheWindow() {
        Schema.Table obs = table("obs", "obs_datetime", "value_datetime", "date_created", "date_voided");
        Map<String, Object> row = row("obs_datetime", "2022-07-01 15:00:00", "value_datetime", "2022-07-01 00:00:00",
                "date_created", "2022-07-01 15:01:02.5", "date_voided", null);

        assertEquals(List.of(new Change("obs_datetime", "2022-07-01 15:00:00", "2022-07-01 11:00:00"),
                        new Change("date_created", "2022-07-01 15:01:02.5", "2022-07-01 11:01:02.5")),
                shift.changes(obs, row, window));
    }

    @Test
    void leavesRowsOutsideTheWindowAlone() {
        Schema.Table encounter = table("encounter", "encounter_datetime", "date_created");

        assertTrue(shift.changes(encounter, row("encounter_datetime", "2021-01-05 09:00:00",
                "date_created", "2021-02-03 06:37:42"), window).isEmpty(), "bulk load before the window");
        assertTrue(shift.changes(encounter, row("encounter_datetime", "2023-09-28 09:00:00",
                "date_created", "2023-09-28 09:00:00"), window).isEmpty(), "entered in local time");
        assertTrue(shift.changes(encounter, row("encounter_datetime", "1970-01-01 00:00:00",
                "date_created", "1970-01-01 00:00:00"), window).isEmpty(), "placeholder");
    }

    @Test
    void changeColumnsSetAfterTheWindowAreAlreadyLocal() {
        Schema.Table person = table("person", "date_created", "date_changed", "date_voided");
        Map<String, Object> row = row("date_created", "2022-03-01 15:00:00", "date_changed", "2024-05-05 10:00:00",
                "date_voided", "2023-01-01 17:00:00");

        assertEquals(List.of("date_created", "date_voided"),
                shift.changes(person, row, window).stream().map(Change::column).toList());
    }

    @Test
    void skipsColumnsAsked() {
        TimeShift skipping = new TimeShift(HAITI, Set.of("ENCOUNTER.encounter_datetime"));
        Schema.Table encounter = table("encounter", "encounter_datetime", "date_created");

        assertEquals(List.of("date_created"), skipping.changes(encounter, row("encounter_datetime",
                "2022-03-01 15:00:00", "date_created", "2022-03-01 15:00:00"), window).stream()
                .map(Change::column).toList());
    }

    @Test
    void parsesInputAndRejectsBadWindows() {
        assertEquals("2023-09-26 00:00:00", TimeShift.format(TimeShift.parseInput("2023-09-26")));
        assertEquals("2023-09-26 14:30:00", TimeShift.format(TimeShift.parseInput("2023-09-26 14:30")));
        assertNull(TimeShift.parse("0000-00-00 00:00:00"));
        assertThrows(IllegalArgumentException.class, () -> TimeShift.parseInput("26/09/2023"));
        assertThrows(IllegalArgumentException.class,
                () -> new Window(TimeShift.parseInput("2023-01-01"), TimeShift.parseInput("2022-01-01")));
    }

    private String local(String utc) {
        return TimeShift.format(shift.toLocal(TimeShift.parse(utc)));
    }

    private static Schema.Table table(String name, String... datetimeColumns) {
        Schema.Table table = new Schema().addTable(name);
        table.addColumn(new Schema.Column(name + "_id", "int", false, true));
        table.addPrimaryKeyColumn(name + "_id");
        for (String column : datetimeColumns) {
            table.addColumn(new Schema.Column(column, "datetime", true, false));
        }
        return table;
    }

    private static Map<String, Object> row(Object... namesAndValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            row.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return row;
    }
}
