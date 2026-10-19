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
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, Options::parse);
        assertThrows(IllegalArgumentException.class, () -> Options.parse("report", "--apply"));
        assertThrows(IllegalArgumentException.class, () -> Options.parse("report", "sync"));
        assertThrows(IllegalArgumentException.class, () -> Options.parse("sync", "--bogus"));
        assertThrows(IllegalArgumentException.class, () -> Options.parse("sync", "--patient"));
        assertThrows(IllegalArgumentException.class, () -> Options.parse("sync", "--config", "--apply"));
    }
}
