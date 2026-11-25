package net.mekomsolutions.dbdiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Table mode, undo and the site key helper on a small database whose datetimes were written as Port-au-Prince wall
 * clock time until 2026-08-27 and are converted to UTC.
 */
class TableTimezoneRunTest {

    @TempDir
    Path dir;

    private TestDatabase db;
    private SyncConfig config;
    private Schema schema;

    @BeforeEach
    void setUp() throws Exception {
        db = TestDatabase.empty();
        db.execute(
                "CREATE TABLE location (location_id INT PRIMARY KEY, name VARCHAR(50), uuid CHAR(38))",
                "CREATE TABLE person (person_id INT PRIMARY KEY, birthdate DATE, date_created DATETIME NOT NULL,"
                        + " uuid CHAR(38) NOT NULL)",
                "CREATE TABLE visit (visit_id INT PRIMARY KEY, patient_id INT NOT NULL, location_id INT,"
                        + " date_started DATETIME NOT NULL, date_stopped DATETIME, date_created DATETIME NOT NULL,"
                        + " date_changed DATETIME, uuid CHAR(38) NOT NULL,"
                        + " FOREIGN KEY (patient_id) REFERENCES person (person_id),"
                        + " FOREIGN KEY (location_id) REFERENCES location (location_id))",
                "CREATE TABLE obs (obs_id INT PRIMARY KEY, person_id INT NOT NULL, obs_datetime DATETIME NOT NULL,"
                        + " value_datetime DATETIME, date_created DATETIME NOT NULL, uuid CHAR(38) NOT NULL,"
                        + " FOREIGN KEY (person_id) REFERENCES person (person_id))",
                "CREATE TABLE notes (note_id INT PRIMARY KEY, date_created DATETIME NOT NULL)",
                "INSERT INTO location VALUES (2, 'Old Site', 'loc-2'), (3, 'Own Site', 'loc-3')",
                // winter value: UTC-5
                "INSERT INTO person VALUES (1, '1980-05-05', '2022-01-15 09:00:00', 'p1')",
                "INSERT INTO person VALUES (3, NULL, '2022-03-01 10:00:00', '[PHANTOM_UUID]')",
                "INSERT INTO person VALUES (4, NULL, '2025-01-10 09:00:00', 'p4')",
                // summer, with a change made after the migration
                "INSERT INTO visit VALUES (1, 1, 2, '2023-07-01 10:00:00', '2023-07-01 11:30:00',"
                        + " '2023-07-01 10:05:00', '2026-09-01 08:00:00', 'v1')",
                // the hour that repeats when clocks go back
                "INSERT INTO visit VALUES (2, 1, 2, '2023-11-05 01:30:00', NULL, '2023-11-05 01:30:00', NULL, 'v2')",
                // the hour skipped when clocks go forward
                "INSERT INTO visit VALUES (3, 1, 2, '2023-03-12 02:30:00', '2023-03-12 04:00:00',"
                        + " '2023-03-12 02:30:00', NULL, 'v3')",
                // stopped after the migration (already UTC), so the converted start ends up after it
                "INSERT INTO visit VALUES (4, 1, 2, '2026-08-26 22:00:00', '2026-08-27 01:00:00',"
                        + " '2026-08-26 22:00:00', NULL, 'v4')",
                // on the placeholder patient
                "INSERT INTO visit VALUES (5, 3, 2, '2022-05-01 10:00:00', NULL, '2022-05-01 10:00:00', NULL, 'v5')",
                // a 1970 placeholder value
                "INSERT INTO visit VALUES (6, 1, 2, '1970-01-01 00:00:00', NULL, '2022-06-01 08:00:00', NULL, 'v6')",
                // the own site's row, excluded by key
                "INSERT INTO visit VALUES (7, 4, 3, '2024-12-01 09:00:00', NULL, '2024-12-01 09:00:00', NULL, 'v7')",
                // outside the range
                "INSERT INTO visit VALUES (8, 1, 2, '2020-12-31 10:00:00', NULL, '2020-12-31 10:00:00', NULL, 'v8')",
                "INSERT INTO visit VALUES (9, 1, 2, '2026-08-27 10:00:00', NULL, '2026-08-27 10:00:00', NULL, 'v9')",
                "INSERT INTO obs VALUES (1, 1, '2022-07-10 08:00:00', '2022-07-10 00:00:00', '2022-07-10 08:00:00',"
                        + " 'o1')",
                "INSERT INTO notes VALUES (1, '2024-11-20 10:00:00'), (2, '2025-02-01 10:00:00'),"
                        + " (3, '2021-02-03 10:00:00'), (4, '2021-05-01 10:00:00')");
        schema = db.schema();
        SyncConfig.DbSettings settings = new SyncConfig.DbSettings("test", db.url(), "", "");
        config = new SyncConfig(settings, settings, Set.of(), dir.resolve("out"));
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    @Test
    void dryRunReportsEverythingAndWritesNothing() throws Exception {
        assertEquals(0, run("--table", "visit", "--convert-edited"));

        assertEquals("2023-07-01 10:00:00", visit(1, "date_started"));
        assertEquals(List.of(
                "visit,1,v1,date_started,2023-07-01 10:00:00,2023-07-01 14:00:00",
                "visit,1,v1,date_stopped,2023-07-01 11:30:00,2023-07-01 15:30:00",
                "visit,1,v1,date_created,2023-07-01 10:05:00,2023-07-01 14:05:00",
                "visit,2,v2,date_started,2023-11-05 01:30:00,2023-11-05 05:30:00",
                "visit,2,v2,date_created,2023-11-05 01:30:00,2023-11-05 05:30:00",
                "visit,3,v3,date_stopped,2023-03-12 04:00:00,2023-03-12 08:00:00",
                "visit,4,v4,date_started,2026-08-26 22:00:00,2026-08-27 02:00:00",
                "visit,4,v4,date_created,2026-08-26 22:00:00,2026-08-27 02:00:00",
                "visit,7,v7,date_started,2024-12-01 09:00:00,2024-12-01 14:00:00",          // winter: +5h
                "visit,7,v7,date_created,2024-12-01 09:00:00,2024-12-01 14:00:00"),
                report("changes.csv").subList(1, 11));
        assertEquals("visit,WOULD_CHANGE,7,5,10,0,2,0,1,0,0,", report("tables.csv").get(1));
        assertEquals(List.of(
                "visit,date_started,4,0,0,1,1,0",
                "visit,date_stopped,2,1,0,0,0,0",
                "visit,date_created,4,0,0,1,1,0",
                "visit,date_changed,0,1,0,0,0,0"), report("columns.csv").subList(1, 5));
        assertEquals("visit,2,v2,date_started,2023-11-05 01:30:00,2023-11-05 05:30:00",
                report("dst-ambiguous.csv").get(1));
        assertEquals("visit,3,v3,date_started,2023-03-12 02:30:00", report("dst-gap.csv").get(1));
        assertEquals("visit,4,v4,date_started,date_stopped,2026-08-26 22:00:00,2026-08-27 01:00:00,"
                + "2026-08-27 02:00:00,2026-08-27 01:00:00,put before the start", report("end-before-start.csv").get(1));
        assertTrue(report("hour-histogram.csv").contains("visit,date_started,10,1,0"));
        assertTrue(report("hour-histogram.csv").contains("visit,date_started,14,0,2"));
    }

    @Test
    void applyConvertsInChunksSkipsWhatItMustAndRunsOnce() throws Exception {
        Path keys = Files.writeString(dir.resolve("keys.csv"), "table,pk_from,pk_to,label\nvisit,7,7,Own Site\n");
        assertEquals(0, run("--tables", "visit,person,obs", "--exclude-keys", keys.toString(), "--chunk-size", "2",
                "--convert-edited", "--apply", "--yes"));

        assertEquals("2023-07-01 14:00:00", visit(1, "date_started"));           // summer: +4h
        assertEquals("2026-09-01 08:00:00", visit(1, "date_changed"));           // after the migration
        assertEquals("2023-11-05 05:30:00", visit(2, "date_started"));           // earlier offset
        assertEquals("2023-03-12 02:30:00", visit(3, "date_started"));           // gap: unchanged
        assertEquals("2023-03-12 08:00:00", visit(3, "date_stopped"));
        assertEquals("2022-05-01 10:00:00", visit(5, "date_started"));           // placeholder parent
        assertEquals("2022-06-01 08:00:00", visit(6, "date_created"));           // 1970 value
        assertEquals("2024-12-01 09:00:00", visit(7, "date_started"));           // excluded key
        assertEquals("2020-12-31 10:00:00", visit(8, "date_started"));           // out of range
        assertEquals("2022-01-15 14:00:00", db.queryString("SELECT date_created FROM person WHERE person_id = 1"));
        assertEquals("1980-05-05", db.queryString("SELECT birthdate FROM person WHERE person_id = 1"));
        assertEquals("2022-03-01 10:00:00", db.queryString("SELECT date_created FROM person WHERE person_id = 3"));
        assertEquals("2022-07-10 12:00:00", db.queryString("SELECT obs_datetime FROM obs"));
        assertEquals("2022-07-10 00:00:00", db.queryString("SELECT value_datetime FROM obs"));
        assertEquals(4 + 2 + 1, db.count(ShiftLog.TABLE));
        assertEquals("date_started: 2023-07-01 10:00:00 -> 2023-07-01 14:00:00; date_stopped: 2023-07-01 11:30:00"
                + " -> 2023-07-01 15:30:00; date_created: 2023-07-01 10:05:00 -> 2023-07-01 14:05:00",
                db.queryString("SELECT changes FROM " + ShiftLog.TABLE + " WHERE table_name = 'visit'"
                        + " AND row_key = '1'"));

        clearReports();
        assertEquals(0, run("--table", "visit", "--exclude-keys", keys.toString(), "--convert-edited", "--apply",
                "--yes"));
        assertEquals("2023-07-01 14:00:00", visit(1, "date_started"));
        // visit 4 now reads 2026-08-27 02:00 UTC, out of the range; the other three are in the log
        assertEquals("visit,NOTHING_TO_CHANGE,6,0,0,1,2,3,0,0,0,", report("tables.csv").get(1));
    }

    @Test
    void skipsRowsOnAPlaceholderParentOfThePlaceholderPatient() throws Exception {
        db.execute("INSERT INTO visit VALUES (11, 3, NULL, '1970-01-01 00:00:00', NULL, '1970-01-01 00:00:00', NULL,"
                        + " 'v11')",
                "CREATE TABLE encounter (encounter_id INT PRIMARY KEY, visit_id INT, encounter_datetime DATETIME,"
                        + " date_created DATETIME NOT NULL, uuid CHAR(38) NOT NULL,"
                        + " FOREIGN KEY (visit_id) REFERENCES visit (visit_id))",
                "INSERT INTO encounter VALUES (1, 11, '2022-02-02 10:00:00', '2022-02-02 10:00:00', 'e1'),"
                        + " (2, 1, '2022-02-02 10:00:00', '2022-02-02 10:00:00', 'e2')",
                // patient has no uuid of its own: the placeholder patient is the placeholder person
                "CREATE TABLE patient (patient_id INT PRIMARY KEY, date_created DATETIME NOT NULL,"
                        + " FOREIGN KEY (patient_id) REFERENCES person (person_id))",
                "INSERT INTO patient VALUES (1, '2022-01-15 09:00:00'), (3, '2022-03-01 10:00:00')",
                "CREATE TABLE patient_note (note_id INT PRIMARY KEY, patient_id INT NOT NULL,"
                        + " date_created DATETIME NOT NULL, FOREIGN KEY (patient_id) REFERENCES patient (patient_id))",
                "INSERT INTO patient_note VALUES (1, 1, '2022-02-02 10:00:00'), (2, 3, '2022-02-02 10:00:00')");
        schema = db.schema();

        assertEquals(0, run("--tables", "encounter,patient_note", "--apply", "--yes"));

        assertEquals("2022-02-02 15:00:00", db.queryString("SELECT date_created FROM patient_note WHERE note_id = 1"));
        assertEquals("2022-02-02 10:00:00", db.queryString("SELECT date_created FROM patient_note WHERE note_id = 2"));

        assertEquals("2022-02-02 10:00:00", db.queryString("SELECT date_created FROM encounter WHERE encounter_id = 1"));
        assertEquals("2022-02-02 15:00:00", db.queryString("SELECT date_created FROM encounter WHERE encounter_id = 2"));
    }

    @Test
    void leavesRowsEditedAfterTheCutoffAndRowsWithoutAnAnchorForReview() throws Exception {
        db.execute("CREATE TABLE log_entry (log_id INT PRIMARY KEY, logged_at DATETIME, date_created DATETIME)",
                "INSERT INTO log_entry VALUES (1, '2022-01-01 10:00:00', NULL), (2, '2022-01-01 10:00:00',"
                        + " '2022-01-01 10:00:00')",
                "CREATE TABLE request (request_id INT PRIMARY KEY, request_datetime DATETIME)",
                "INSERT INTO request VALUES (1, '2022-01-01 10:00:00')");
        schema = db.schema();

        assertEquals(0, run("--tables", "visit,log_entry,request", "--apply", "--yes"));

        assertEquals("2023-07-01 10:00:00", visit(1, "date_started"));           // changed 2026-09-01: left
        assertEquals("2023-11-05 05:30:00", visit(2, "date_started"));
        assertEquals("visit,1,v1,date_changed,2026-09-01 08:00:00,date_started=2023-07-01 10:00:00;"
                + " date_stopped=2023-07-01 11:30:00; date_created=2023-07-01 10:05:00;"
                + " date_changed=2026-09-01 08:00:00", report("edited-after-cutoff.csv").get(1));
        assertEquals("2022-01-01 10:00:00", db.queryString("SELECT logged_at FROM log_entry WHERE log_id = 1"));
        assertEquals("2022-01-01 15:00:00", db.queryString("SELECT logged_at FROM log_entry WHERE log_id = 2"));
        assertEquals("log_entry,1,,date_created", report("null-date-created.csv").get(1));
        List<String> tables = report("tables.csv");
        assertEquals("visit,CHANGED,7,4,7,0,2,0,1,1,0,", tables.get(1));
        assertEquals("log_entry,CHANGED,1,1,2,0,0,0,0,0,1,", tables.get(2));
        assertTrue(tables.get(3).startsWith("request,SKIPPED,,,,,,,,,," + TimezoneTables.NO_ANCHOR), tables.get(3));
        assertEquals("2022-01-01 10:00:00", db.queryString("SELECT request_datetime FROM request"));

        clearReports();
        assertEquals(0, run("--table", "request", "--anchor-column", "request.request_datetime", "--apply", "--yes"));
        assertEquals("2022-01-01 15:00:00", db.queryString("SELECT request_datetime FROM request"));
    }

    @Test
    void undoRestoresByRunIdOrTable() throws Exception {
        assertEquals(0, run("--table", "visit", "--convert-edited", "--apply", "--yes"));
        String runId = db.queryString("SELECT MIN(run_id) FROM " + ShiftLog.TABLE);
        assertEquals(0, run("--table", "person", "--apply", "--yes"));

        clearReports();
        assertEquals(0, undo("--undo", "--run-id", runId));                      // dry run
        assertEquals("2023-07-01 14:00:00", visit(1, "date_started"));
        assertTrue(report("undo.csv").contains("visit,1," + runId
                + ",date_started,2023-07-01 14:00:00,2023-07-01 10:00:00,WOULD_RESTORE"));

        db.execute("UPDATE visit SET date_stopped = '2023-07-02 00:00:00' WHERE visit_id = 1"); // edited since
        clearReports();
        assertEquals(0, undo("--undo", "--run-id", runId, "--apply", "--yes", "--chunk-size", "2"));
        assertEquals("2023-07-01 14:00:00", visit(1, "date_started"));           // changed since: left alone
        assertEquals("2023-11-05 01:30:00", visit(2, "date_started"));
        assertEquals("2026-08-26 22:00:00", visit(4, "date_started"));
        assertEquals("1", db.queryString("SELECT COUNT(*) FROM " + ShiftLog.TABLE + " WHERE table_name = 'visit'"));
        assertEquals("2022-01-15 14:00:00", db.queryString("SELECT date_created FROM person WHERE person_id = 1"));

        clearReports();
        assertEquals(0, undo("--undo", "--table", "person", "--apply", "--yes"));
        assertEquals("2022-01-15 09:00:00", db.queryString("SELECT date_created FROM person WHERE person_id = 1"));
    }

    @Test
    void addsTheRunIdColumnToAnOlderLedger() throws Exception {
        db.execute("CREATE TABLE " + ShiftLog.TABLE + " (table_name VARCHAR(64) NOT NULL, row_key VARCHAR(255)"
                        + " NOT NULL, patient_uuid CHAR(38) NOT NULL, changes TEXT NOT NULL, shifted_at DATETIME NOT NULL,"
                        + " PRIMARY KEY (table_name, row_key))",
                "INSERT INTO " + ShiftLog.TABLE + " VALUES ('visit', '2', 'p1', 'date_started: 2023-11-05 01:30:00"
                        + " -> 2023-11-05 06:30:00', '2026-10-23 10:00:00')");

        assertEquals(0, run("--table", "visit", "--apply", "--yes"));

        assertEquals("2023-11-05 01:30:00", visit(2, "date_started"));          // logged by the old run: skipped
        assertEquals(null, db.queryString("SELECT run_id FROM " + ShiftLog.TABLE + " WHERE row_key = '2'"));
        assertEquals("1", db.queryString("SELECT COUNT(DISTINCT run_id) FROM " + ShiftLog.TABLE));
    }

    @Test
    void allTablesTakesEveryUsableTableButExcludedAndBookkeepingOnes() throws Exception {
        db.execute("CREATE TABLE liquibasechangelog (id INT PRIMARY KEY, dateexecuted DATETIME)");
        Schema s = db.schema();
        SyncConfig excluding = new SyncConfig(config.source(), config.target(), Set.of("obs"), config.outputDir());
        List<String> names = TimezoneTables.select(s, excluding, List.of(), true).stream()
                .map(c -> c.table().name() + (c.usable() ? "" : " (" + c.problem() + ")")).toList();
        assertEquals(List.of("notes", "person", "visit"), names);
        assertEquals(List.of("visit", "obs (" + TimezoneTables.EXCLUDED + ")"),
                TimezoneTables.select(s, excluding, List.of("visit", "obs"), false).stream()
                        .map(c -> c.table().name() + (c.usable() ? "" : " (" + c.problem() + ")")).toList());
    }

    @Test
    void suggestsTheKeyRangesOfOneLocationsRows() throws Exception {
        db.execute("INSERT INTO person VALUES (5, NULL, '2025-02-01 09:00:00', 'p5')",
                "INSERT INTO visit VALUES (10, 5, 3, '2025-02-01 09:00:00', NULL, '2025-02-01 09:00:00', NULL, 'v10')");
        Options o = Options.parse("timezone", "--suggest-site-keys", "--site-location", "Own Site", "--tables",
                "visit,person,notes");
        assertEquals(0, new SiteKeySuggester(o, config, c -> schema).execute());

        List<String> suggested = report("site-keys-suggested.csv");
        assertEquals("visit,7,10,Own Site,location_id,2,4,2,0", suggested.get(1));
        assertEquals("person,4,5,Own Site,person's visits and encounters,2,2,0,0", suggested.get(2));
        assertTrue(suggested.get(3).startsWith("\"# notes: no location path; date_created blocks by key: 1-2 (2"
                + " rows, 2024-11-20..2025-02-01); 3-4 (2 rows, 2021-02-03..2021-05-01)"), suggested.get(3));
        assertTrue(report("site-key-blocks.csv").contains("visit,Old Site (2),1,9,8,location_id"));

        // the suggestion can be passed back as it is
        TimezoneTables.ExcludedKeys keys = TimezoneTables.ExcludedKeys.load(reportFile("site-keys-suggested.csv"));
        assertEquals("Own Site", keys.find("visit", 8).label());
        assertEquals(null, keys.find("visit", 11));
        assertTrue(keys.of("notes").isEmpty());
    }

    private int run(String... args) throws Exception {
        String[] all = Stream.concat(Stream.of("timezone", "--zone", "America/Port-au-Prince", "--to", "utc",
                "--from", "2021-01-01", "--before", "2026-08-27"), Stream.of(args)).toArray(String[]::new);
        return new TableTimezoneRun(Options.parse(all), config, c -> schema).execute();
    }

    private int undo(String... args) throws Exception {
        String[] all = Stream.concat(Stream.of("timezone"), Stream.of(args)).toArray(String[]::new);
        return new TimezoneUndo(Options.parse(all), config, c -> schema).execute();
    }

    private String visit(int id, String column) throws Exception {
        return db.queryString("SELECT " + column + " FROM visit WHERE visit_id = " + id);
    }

    private void clearReports() throws Exception {
        Path out = dir.resolve("out");
        if (Files.exists(out)) {
            Files.move(out, dir.resolve("old-" + System.nanoTime()));
        }
    }

    private Path reportFile(String file) throws Exception {
        try (Stream<Path> runs = Files.list(dir.resolve("out"))) {
            return runs.map(p -> p.resolve(file)).filter(Files::exists).findFirst().orElseThrow();
        }
    }

    private List<String> report(String file) throws Exception {
        return Files.readAllLines(reportFile(file)).stream().map(String::stripTrailing).toList();
    }
}
