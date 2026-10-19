package net.mekomsolutions.dbdiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncRunTest {

    @TempDir
    Path dir;

    @Test
    void reportCoversEveryPatientAndWritesNothing() throws Exception {
        try (SyncFixture f = new SyncFixture(dir)) {
            int exit = run(f, "report");

            assertEquals(0, exit);
            assertEquals(List.of(
                    "patient_uuid,status,source_rows,missing_in_target,syncable,blocked,inserted,error",
                    "person-p1,MISSING,8,3,1,2,0,",
                    "person-p2,MISSING,8,11,11,0,0,",
                    "person-p3,MISSING,3,5,5,0,0,"), lines("patients.csv"));
            assertEquals(4, f.target.count("person"));
        }
    }

    @Test
    void applyKeepsGoingAfterAFailedPatientAndExitsWithOne() throws Exception {
        Path patients = Files.writeString(dir.resolve("patients.txt"), "person-p3\nperson-p1\nperson-p2\n");
        try (SyncFixture f = new SyncFixture(dir.resolve("output"))) {
            int exit = run(f, "sync", "--apply", "--yes", "--patients-file", patients.toString());

            assertEquals(1, exit);
            List<String> statuses = lines("patients.csv").stream().skip(1)
                    .map(l -> l.substring(0, l.indexOf(',', l.indexOf(',') + 1))).toList();
            assertEquals(List.of("person-p3,FAILED", "person-p1,SYNCED", "person-p2,SYNCED"), statuses);
            assertTrue(f.target.count("obs WHERE uuid = 'obs-p2-weight-child'") == 1);
        }
    }

    @Test
    void reportFilesListMissingAndBlockedRowsPerPatient() throws Exception {
        try (SyncFixture f = new SyncFixture(dir)) {
            run(f, "report", "--patient", "person-p1");

            assertEquals(List.of("table,source_rows,missing_in_target,syncable,blocked,inserted",
                    "encounter,1,0,0,0,0", "obs,4,3,1,2,0", "patient,1,0,0,0,0", "person,1,0,0,0,0",
                    "person_name,1,0,0,0,0"), lines("summary.csv"));
            List<String> blocked = lines("blocked.csv");
            assertEquals(3, blocked.size());
            assertTrue(blocked.get(1).startsWith("person-p1,obs,obs-p1-height"), blocked.get(1));
            assertTrue(lines("missing/obs.csv").get(1).startsWith("person-p1,syncable,4,"));
        }
    }

    @Test
    void metadataCommandWritesTheFindingsAndSuggestedDecisionsOnly() throws Exception {
        try (SyncFixture f = new SyncFixture(dir)) {
            int exit = run(f, "metadata");

            assertEquals(0, exit);
            assertEquals(List.of("table,source_uuid,name,action,target_uuid", "concept,concept-height-source-only,,COPY,"),
                    lines("metadata-decisions.csv"));
            assertEquals(1, f.target.count("obs"));
        }
    }

    private int run(SyncFixture f, String... args) throws Exception {
        return new SyncRun(Options.parse(args), f.config, connection -> connection == f.databases.source()
                ? f.sourceSchema : f.targetSchema).execute();
    }

    /** Lines of a file in the only report folder written so far. */
    private List<String> lines(String file) throws Exception {
        Path output = Files.isDirectory(dir.resolve("output")) ? dir.resolve("output") : dir;
        try (Stream<Path> runs = Files.list(output)) {
            Path report = runs.filter(p -> Files.exists(p.resolve(file))).findFirst().orElseThrow();
            return Files.readAllLines(report.resolve(file)).stream().map(String::stripTrailing).toList();
        }
    }
}
