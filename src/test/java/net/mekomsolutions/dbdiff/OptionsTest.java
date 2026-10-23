package net.mekomsolutions.dbdiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class OptionsTest {

    @Test
    void reportIsReadOnlyWithDefaults() {
        Options o = Options.parse("report");

        assertEquals(RunMode.REPORT, o.mode());
        assertEquals(Path.of("config", "sync.properties"), o.config());
        assertTrue(o.patientUuids().isEmpty());
        assertNull(o.patientsFile());
        assertFalse(o.assumeYes());
    }

    @Test
    void syncIsADryRunUnlessApplied() {
        assertEquals(RunMode.DRY_RUN, Options.parse("sync").mode());
        assertEquals(RunMode.APPLY, Options.parse("sync", "--apply").mode());
        assertEquals(RunMode.APPLY, Options.parse("--apply", "sync").mode());
    }

    @Test
    void patientsCanBeCommaSeparatedAndRepeated() {
        Options o = Options.parse("sync", "--patient", "a, b,,c", "--patient", "d");

        assertEquals(List.of("a", "b", "c", "d"), o.patientUuids());
    }

    @Test
    void readsFileConfigAndConfirmationOptions() {
        Options o = Options.parse("sync", "--apply", "--yes", "--patients-file", "p.txt", "--config", "x.properties");

        assertEquals(Path.of("p.txt"), o.patientsFile());
        assertEquals(Path.of("x.properties"), o.config());
        assertTrue(o.assumeYes());
    }

    @Test
    void metadataComparesEveryMetadataRowAndTakesNoPatients() {
        assertEquals(RunMode.METADATA, Options.parse("metadata").mode());
        assertThrows(IllegalArgumentException.class, () -> Options.parse("metadata", "--patient", "p1"));
        assertThrows(IllegalArgumentException.class, () -> Options.parse("metadata", "--apply"));
    }

    @Test
    void timezoneIsADryRunOnTheTargetUnlessToldOtherwise() {
        Options o = Options.parse("timezone", "--cutoffs", "c.csv", "--zone", "America/Port-au-Prince", "--to", "local");

        assertEquals(RunMode.TIMEZONE, o.mode());
        assertEquals("target", o.timezone().database());
        assertEquals(Path.of("c.csv"), o.timezone().cutoffsFile());
        assertEquals("America/Port-au-Prince", o.timezone().zone().getId());
        assertTrue(o.timezone().skipped().isEmpty());
        assertFalse(o.timezone().toUtc());
        assertTrue(Options.parse("timezone", "--before", "2026-08-28", "--zone", "UTC", "--to", "utc").timezone().toUtc());
        assertNull(Options.parse("sync").timezone());

        Options applied = Options.parse("timezone", "--apply", "--db", "source", "--from", "2021-02-04",
                "--before", "2023-09-26 12:00", "--zone", "UTC", "--to", "local", "--skip-columns", "obs.value_datetime,visit.date_stopped");
        assertEquals(RunMode.TIMEZONE_APPLY, applied.mode());
        assertEquals("source", applied.timezone().database());
        assertEquals("2023-09-26 12:00:00", TimeShift.format(applied.timezone().before()));
        assertEquals(2, applied.timezone().skipped().size());
    }

    @Test
    void timezoneNeedsAZoneADirectionAndAWindow() {
        assertThrows(IllegalArgumentException.class, () -> Options.parse("timezone", "--before", "2022-01-01"));
        assertThrows(IllegalArgumentException.class, () -> timezone("--to", "sideways", "--before", "2022-01-01"));
        assertThrows(IllegalArgumentException.class, () -> timezone("--to", "local"));
        assertThrows(IllegalArgumentException.class, () -> timezone("--to", "local", "--from", "2021-01-01"));
        assertThrows(IllegalArgumentException.class,
                () -> timezone("--to", "local", "--from", "2023-01-01", "--before", "2022-01-01"));
        assertThrows(IllegalArgumentException.class, () -> timezone("--to", "local", "--before", "yesterday"));
        assertThrows(IllegalArgumentException.class,
                () -> timezone("--to", "local", "--before", "2022-01-01", "--db", "other"));
        assertThrows(IllegalArgumentException.class, () -> Options.parse("timezone", "--to", "local", "--before",
                "2022-01-01", "--zone", "Mars/Olympus"));
        assertThrows(IllegalArgumentException.class, () -> Options.parse("sync", "--before", "2022-01-01"));
    }

    private static Options timezone(String... args) {
        String[] all = new String[args.length + 3];
        all[0] = "timezone";
        all[1] = "--zone";
        all[2] = "UTC";
        System.arraycopy(args, 0, all, 3, args.length);
        return Options.parse(all);
    }

    @Test
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, Options::parse);
        assertThrows(IllegalArgumentException.class, () -> Options.parse("report", "--apply"));
        assertThrows(IllegalArgumentException.class, () -> Options.parse("report", "sync"));
        assertThrows(IllegalArgumentException.class, () -> Options.parse("sync", "--bogus"));
        assertThrows(IllegalArgumentException.class, () -> Options.parse("sync", "--patient"));
        assertThrows(IllegalArgumentException.class, () -> Options.parse("sync", "--config", "--apply"));
    }
}
