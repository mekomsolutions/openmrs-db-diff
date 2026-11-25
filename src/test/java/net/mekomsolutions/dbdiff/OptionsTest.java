package net.mekomsolutions.dbdiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

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
        Options o = Options.parse("timezone", "--table", "visit", "--before", "2026-08-28", "--zone",
                "America/Port-au-Prince", "--to", "local");

        assertEquals(RunMode.TIMEZONE, o.mode());
        assertEquals("target", o.timezone().database());
        assertEquals("America/Port-au-Prince", o.timezone().zone().getId());
        assertFalse(o.timezone().toUtc());
        assertNull(Options.parse("sync").timezone());

        Options applied = Options.parse("timezone", "--all-tables", "--apply", "--db", "source", "--from",
                "2021-02-04", "--before", "2023-09-26 12:00", "--zone", "UTC", "--to", "utc", "--skip-columns",
                "obs.value_datetime,visit.date_stopped");
        assertEquals(RunMode.TIMEZONE_APPLY, applied.mode());
        assertEquals("source", applied.timezone().database());
        assertTrue(applied.timezone().toUtc());
        assertEquals("2023-09-26 12:00:00", TimeShift.format(applied.timezone().before()));
        assertEquals(2, applied.timezone().skipped().size());
    }

    @Test
    void timezoneNeedsTablesAZoneADirectionAndARange() {
        assertThrows(IllegalArgumentException.class, () -> timezone("--to", "utc", "--before", "2022-01-01"));
        assertThrows(IllegalArgumentException.class,
                () -> Options.parse("timezone", "--table", "visit", "--before", "2022-01-01"));
        assertThrows(IllegalArgumentException.class,
                () -> timezone("--table", "visit", "--to", "sideways", "--before", "2022-01-01"));
        assertThrows(IllegalArgumentException.class, () -> timezone("--table", "visit", "--to", "local"));
        assertThrows(IllegalArgumentException.class,
                () -> timezone("--table", "visit", "--to", "local", "--from", "2021-01-01"));
        assertThrows(IllegalArgumentException.class,
                () -> timezone("--table", "visit", "--to", "local", "--from", "2023-01-01", "--before", "2022-01-01"));
        assertThrows(IllegalArgumentException.class,
                () -> timezone("--table", "visit", "--to", "local", "--before", "yesterday"));
        assertThrows(IllegalArgumentException.class,
                () -> timezone("--table", "visit", "--to", "local", "--before", "2022-01-01", "--db", "other"));
        assertThrows(IllegalArgumentException.class, () -> Options.parse("timezone", "--table", "visit", "--to",
                "local", "--before", "2022-01-01", "--zone", "Mars/Olympus"));
        assertThrows(IllegalArgumentException.class,
                () -> timezone("--table", "visit", "--to", "utc", "--before", "2022-01-01", "--cutoffs", "c.csv"));
        assertThrows(IllegalArgumentException.class, () -> Options.parse("sync", "--before", "2022-01-01"));
    }

    @Test
    void timezoneTableModeTakesOneRangeAndSkipsObsValueDatetimeByDefault() {
        Options o = timezone("--to", "utc", "--tables", "visit,encounter", "--table", "obs", "--from", "2021-01-01",
                "--before", "2026-08-27", "--exclude-keys", "keys.csv", "--chunk-size", "100");
        assertEquals(Options.Action.TABLES, o.timezone().action());
        assertEquals(List.of("visit", "encounter", "obs"), o.timezone().tables());
        assertEquals(Set.of("obs.value_datetime"), o.timezone().skipped());
        assertEquals(Path.of("keys.csv"), o.timezone().excludeKeysFile());
        assertEquals(100, o.timezone().chunkSize());
        assertTrue(timezone("--to", "utc", "--all-tables", "--before", "2026-08-27", "--skip-columns", "")
                .timezone().skipped().isEmpty());
        assertEquals(5000, timezone("--to", "utc", "--all-tables", "--before", "2026-08-27").timezone().chunkSize());
        Options edited = timezone("--to", "utc", "--all-tables", "--before", "2026-08-27", "--convert-edited",
                "--anchor-column", "Request.request_datetime");
        assertTrue(edited.timezone().convertEdited());
        assertEquals(java.util.Map.of("request", "request_datetime"), edited.timezone().anchors());
        assertFalse(o.timezone().convertEdited());
        assertThrows(IllegalArgumentException.class, () -> timezone("--to", "utc", "--all-tables", "--before",
                "2026-08-27", "--anchor-column", "request"));
        assertThrows(IllegalArgumentException.class,
                () -> Options.parse("timezone", "--undo", "--table", "visit", "--convert-edited"));

        assertThrows(IllegalArgumentException.class, () -> timezone("--to", "utc", "--table", "visit"));
        assertThrows(IllegalArgumentException.class,
                () -> timezone("--to", "utc", "--table", "visit", "--all-tables", "--before", "2026-08-27"));
        assertThrows(IllegalArgumentException.class,
                () -> timezone("--to", "utc", "--table", "visit", "--before", "2026-08-27", "--patient", "x"));
        assertThrows(IllegalArgumentException.class,
                () -> timezone("--to", "utc", "--table", "visit", "--before", "2026-08-27", "--chunk-size", "0"));
        assertThrows(IllegalArgumentException.class,
                () -> Options.parse("timezone", "--undo", "--table", "visit", "--exclude-keys", "k.csv"));
    }

    @Test
    void timezoneUndoAndSiteKeysNeedTheirOwnOptions() {
        Options undo = Options.parse("timezone", "--undo", "--run-id", "20261124-101010-000", "--apply");
        assertEquals(Options.Action.UNDO, undo.timezone().action());
        assertEquals(RunMode.TIMEZONE_APPLY, undo.mode());
        assertEquals("20261124-101010-000", undo.timezone().runId());
        assertEquals(List.of("visit"), Options.parse("timezone", "--undo", "--table", "visit").timezone().tables());
        assertThrows(IllegalArgumentException.class, () -> Options.parse("timezone", "--undo"));
        assertThrows(IllegalArgumentException.class, () -> timezone("--to", "utc", "--table", "visit", "--before",
                "2026-08-27", "--run-id", "x"));

        Options suggest = Options.parse("timezone", "--suggest-site-keys", "--site-location", "Main");
        assertEquals(Options.Action.SUGGEST_SITE_KEYS, suggest.timezone().action());
        assertEquals("Main", suggest.timezone().siteLocation());
        assertThrows(IllegalArgumentException.class, () -> Options.parse("timezone", "--suggest-site-keys"));
        assertThrows(IllegalArgumentException.class, () -> Options.parse("timezone", "--suggest-site-keys",
                "--site-location", "Main", "--apply"));
        assertThrows(IllegalArgumentException.class, () -> Options.parse("sync", "--table", "visit"));
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
