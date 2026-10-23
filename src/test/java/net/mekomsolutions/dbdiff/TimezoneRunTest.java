package net.mekomsolutions.dbdiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TimezoneRunTest {

    @TempDir
    Path dir;

    @Test
    void dryRunListsTheValuesInTheLocationsWindowAndWritesNothing() throws Exception {
        try (SyncFixture f = new SyncFixture(dir.resolve("dry"))) {
            int exit = run(f, "timezone", "--patient", "person-p1");

            assertEquals(0, exit);
            assertEquals(List.of(
                    "patient_uuid,status,location,window,rows,rows_to_shift,values_to_shift,already_shifted,"
                            + "rows_without_key,detail",
                    "person-p1,WOULD_SHIFT,Clinic,2021-01-01 00:00:00 .. 2022-01-01 00:00:00,8,5,7,0,0,"),
                    lines("dry", "timezone-patients.csv"));
            assertEquals("person-p1,encounter,1,enc-p1,encounter_datetime,2021-02-02 10:00:00,2021-02-02 05:00:00",
                    lines("dry", "timezone-changes.csv").stream().filter(l -> l.contains(",encounter,")).findFirst()
                            .orElseThrow());
            assertEquals("2021-02-02 10:00:00", f.source.queryString("SELECT date_created FROM person WHERE person_id = 2"));
        }
    }

    @Test
    void applyShiftsOnceAndRecordsEveryRow() throws Exception {
        try (SyncFixture f = new SyncFixture(dir.resolve("first"))) {
            assertEquals(0, run(f, "timezone", "--apply", "--yes"));

            assertEquals("2021-02-02 05:00:00", f.source.queryString("SELECT date_created FROM person WHERE person_id = 2"));
            assertEquals("2021-02-02 05:00:00", f.source.queryString("SELECT obs_datetime FROM obs WHERE obs_id = 1"));
            assertEquals("2022-04-04 11:00:00", f.source.queryString("SELECT obs_datetime FROM obs WHERE obs_id = 4"));
            assertEquals(5, f.source.count(TimezoneRun.LEDGER));
            assertEquals("encounter_datetime: 2021-02-02 10:00:00 -> 2021-02-02 05:00:00; date_created: "
                    + "2021-02-02 10:00:00 -> 2021-02-02 05:00:00", f.source.queryString(
                    "SELECT changes FROM " + TimezoneRun.LEDGER + " WHERE table_name = 'encounter'"));
            List<String> statuses = lines("first", "timezone-patients.csv").stream().skip(1)
                    .map(l -> l.substring(0, l.indexOf(',', l.indexOf(',') + 1))).toList();
            assertEquals(List.of("person-p1,SHIFTED", "person-p2,NOTHING_TO_SHIFT", "person-p3,NOTHING_TO_SHIFT"), statuses);

            Files.move(dir.resolve("first"), dir.resolve("again-old"));
            run(f, "timezone", "--apply", "--yes", "--patient", "person-p1");
            assertEquals("person-p1,NOTHING_TO_SHIFT,Clinic,2021-01-01 00:00:00 .. 2022-01-01 00:00:00,8,0,0,5,0,",
                    lines("first", "timezone-patients.csv").get(1));
            assertEquals("2021-02-02 05:00:00", f.source.queryString("SELECT date_created FROM person WHERE person_id = 2"));
        }
    }

    @Test
    void rejectsUnknownLocations() throws Exception {
        Path cutoffs = Files.writeString(dir.resolve("bad.csv"), "location,from,before\nNowhere,,2022-01-01\n");
        try (SyncFixture f = new SyncFixture(dir)) {
            assertThrows(IllegalArgumentException.class, () -> new TimezoneRun(Options.parse("timezone", "--db",
                    "source", "--cutoffs", cutoffs.toString(), "--zone", "UTC", "--to", "local"), f.config, c -> f.sourceSchema).execute());
        }
    }

    /**
     * Runs on the fixture source: p1 is seen at Clinic, which has a 2021 window; p2 too, but only after it; p3 has
     * no location, and the default window ends before they were registered.
     */
    private int run(SyncFixture f, String... args) throws Exception {
        Path cutoffs = Files.writeString(dir.resolve("cutoffs.csv"),
                "location,from,before\nClinic,2021-01-01,2022-01-01\n*,,2020-06-01\n");
        String[] all = Stream.concat(Stream.of(args), Stream.of("--db", "source", "--cutoffs", cutoffs.toString(),
                "--zone", "America/Port-au-Prince", "--to", "local"))
                .toArray(String[]::new);
        return new TimezoneRun(Options.parse(all), f.config, c -> f.sourceSchema).execute();
    }

    private List<String> lines(String output, String file) throws Exception {
        try (Stream<Path> runs = Files.list(dir.resolve(output))) {
            Path report = runs.filter(p -> Files.exists(p.resolve(file))).findFirst().orElseThrow();
            return Files.readAllLines(report.resolve(file)).stream().map(String::stripTrailing).toList();
        }
    }
}
