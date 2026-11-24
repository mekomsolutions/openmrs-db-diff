package net.mekomsolutions.dbdiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import net.mekomsolutions.dbdiff.TimeShift.Conversion;
import net.mekomsolutions.dbdiff.TimeShift.Kind;
import net.mekomsolutions.dbdiff.TimeShift.Window;

class TimeShiftTest {

    private static final ZoneId HAITI = ZoneId.of("America/Port-au-Prince");

    private final TimeShift toLocal = new TimeShift(HAITI, false);
    private final TimeShift toUtc = new TimeShift(HAITI, true);

    @Test
    void followsTheZonesDaylightSavingTimeRules() {
        // UTC-5 in winter, UTC-4 in summer from 2017 on; Haiti kept UTC-5 all of 2016.
        assertEquals("2022-01-10 10:00:00", local("2022-01-10 15:00:00"));
        assertEquals("2022-07-01 11:00:00", local("2022-07-01 15:00:00"));
        assertEquals("2016-07-01 10:00:00", local("2016-07-01 15:00:00"));
        assertEquals("2022-05-01 00:00:00", local("2022-05-01 04:00:00"));
    }

    @Test
    void convertFlagsTheHoursDaylightSavingTimeRepeatsOrSkips() {
        assertEquals(new Conversion(TimeShift.parse("2024-01-15 15:00:00"), Kind.OK),
                toUtc.convert(TimeShift.parse("2024-01-15 10:00:00")));          // winter: UTC-5
        assertEquals(new Conversion(TimeShift.parse("2024-07-15 14:00:00"), Kind.OK),
                toUtc.convert(TimeShift.parse("2024-07-15 10:00:00")));          // summer: UTC-4
        assertEquals(new Conversion(TimeShift.parse("2024-11-03 05:30:00"), Kind.AMBIGUOUS),
                toUtc.convert(TimeShift.parse("2024-11-03 01:30:00")));          // first occurrence, UTC-4
        assertEquals(new Conversion(TimeShift.parse("2024-03-10 02:30:00"), Kind.GAP),
                toUtc.convert(TimeShift.parse("2024-03-10 02:30:00")));          // never happened: unchanged
        assertEquals(new Conversion(TimeShift.parse("2024-11-03 01:30:00"), Kind.OK),
                toLocal.convert(TimeShift.parse("2024-11-03 05:30:00")));        // to local: always clear
    }

    @Test
    void parsesInputAndRejectsBadWindows() {
        assertEquals("2023-09-26 00:00:00", TimeShift.format(TimeShift.parseInput("2023-09-26")));
        assertEquals("2023-09-26 14:30:00", TimeShift.format(TimeShift.parseInput("2023-09-26 14:30")));
        assertEquals("2022-07-01 15:01:02.5", TimeShift.format(TimeShift.parse("2022-07-01 15:01:02.5")));
        assertNull(TimeShift.parse("0000-00-00 00:00:00"));
        assertThrows(IllegalArgumentException.class, () -> TimeShift.parseInput("26/09/2023"));
        assertThrows(IllegalArgumentException.class,
                () -> new Window(TimeShift.parseInput("2023-01-01"), TimeShift.parseInput("2022-01-01")));
    }

    private String local(String utc) {
        return TimeShift.format(toLocal.toLocal(TimeShift.parse(utc)));
    }
}
