package net.mekomsolutions.dbdiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.mekomsolutions.dbdiff.MetadataCheck.Finding;
import net.mekomsolutions.dbdiff.MetadataDecisions.Action;

class MetadataCheckTest {

    @TempDir
    Path dir;

    @Test
    void findsMetadataTheTargetLacksAndHowMuchPatientDataUsesIt() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            List<Finding> findings = check(f);

            assertEquals(1, findings.size());
            Finding height = findings.get(0);
            assertEquals("concept", height.table().name());
            assertEquals("concept-height-source-only", height.uuid());
            assertEquals(1, height.usedBy());
            assertEquals(Set.of("obs"), height.usedIn());
            assertTrue(height.needed());
            assertEquals(Action.COPY, height.suggestion());
        }
    }

    @Test
    void suggestsMappingToTheOneTargetRowWithTheSameName() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            useWardForPatientTwo(f);
            f.target.execute("INSERT INTO location VALUES (2, 'Ward', 'location-ward-h9')");

            Finding ward = find(check(f), "location-ward-h4");

            assertEquals("Ward", ward.name());
            assertEquals(List.of("location-ward-h9"), ward.sameNameInTarget());
            assertEquals(Action.MAP, ward.suggestion());
        }
    }

    @Test
    void leavesTheChoiceToAPersonWhenSeveralTargetRowsHaveTheName() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            useWardForPatientTwo(f);
            f.target.execute("INSERT INTO location VALUES (2, 'Ward', 'location-ward-a')",
                    "INSERT INTO location VALUES (3, 'Ward', 'location-ward-b')");

            Finding ward = find(check(f), "location-ward-h4");

            assertEquals(List.of("location-ward-a", "location-ward-b"), ward.sameNameInTarget());
            assertEquals(Action.SKIP, ward.suggestion());
        }
    }

    @Test
    void listsUnusedMetadataWithoutSuggestingAnything() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            f.source.execute("INSERT INTO location VALUES (2, 'Store', 'location-store')");

            Finding store = find(check(f), "location-store");

            assertEquals(0, store.usedBy());
            assertFalse(store.needed());
        }
    }

    @Test
    void aMissingParentOfNeededMetadataIsNeededToo() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            for (TestDatabase db : List.of(f.source, f.target)) {
                db.execute("ALTER TABLE location ADD COLUMN parent_location INT",
                        "ALTER TABLE location ADD FOREIGN KEY (parent_location) REFERENCES location (location_id)");
            }
            f.source.execute("INSERT INTO location VALUES (2, 'Hospital', 'location-hospital', NULL)",
                    "INSERT INTO location VALUES (3, 'Ward', 'location-ward-h4', 2)",
                    "UPDATE encounter SET location_id = 3 WHERE uuid = 'enc-p2'");

            List<Finding> findings = new MetadataCheck(f.databases.source(), f.databases.targetRead(),
                    f.source.schema(), f.target.schema(), f.config).run();

            Finding hospital = find(findings, "location-hospital");
            assertEquals(0, hospital.usedBy());
            assertEquals(List.of("location location-ward-h4"), hospital.neededBy());
            assertTrue(hospital.needed());
        }
    }

    @Test
    void comparesOnlyTheListedMetadataTables() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            useWardForPatientTwo(f);
            SyncConfig onlyLocations = new SyncConfig(f.config.source(), f.config.target(), f.config.excludedTables(),
                    f.config.outputDir(), Set.of("location", "obs", "no_such_table"), null);

            List<Finding> findings = new MetadataCheck(f.databases.source(), f.databases.targetRead(),
                    f.sourceSchema, f.targetSchema, onlyLocations).run();

            assertEquals(List.of("location-ward-h4"), findings.stream().map(Finding::uuid).toList());
        }
    }

    @Test
    void refusesToRunWithoutAMetadataTablesList() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            SyncConfig noList = new SyncConfig(f.config.source(), f.config.target(), f.config.excludedTables(),
                    f.config.outputDir());

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new MetadataCheck(
                    f.databases.source(), f.databases.targetRead(), f.sourceSchema, f.targetSchema, noList).run());
            assertTrue(e.getMessage().startsWith("No metadata tables are listed"), e.getMessage());
        }
    }

    @Test
    void writesTheFindingsAndADecisionsFileTheSyncCanRead() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            useWardForPatientTwo(f);
            f.target.execute("INSERT INTO location VALUES (2, 'Ward', 'location-ward-h9')");
            f.source.execute("INSERT INTO location VALUES (4, 'Store', 'location-store')");

            MetadataCheck.write(dir, check(f));

            assertEquals(List.of("table,source_uuid,name,action,target_uuid",
                    "concept,concept-height-source-only,,COPY,",
                    "location,location-ward-h4,Ward,MAP,location-ward-h9"),
                    Files.readAllLines(dir.resolve("metadata-decisions.csv")));
            assertTrue(Files.readAllLines(dir.resolve("metadata.csv")).get(3).startsWith("location,location-store,"));
            MetadataDecisions decisions = MetadataDecisions.load(dir.resolve("metadata-decisions.csv"));
            assertEquals(Action.MAP, decisions.action("location", "location-ward-h4"));
        }
    }

    private static List<Finding> check(SyncFixture f) throws Exception {
        return new MetadataCheck(f.databases.source(), f.databases.targetRead(), f.sourceSchema, f.targetSchema,
                f.config).run();
    }

    /** Patient two's encounter happened at a ward the target knows under another uuid, or not at all. */
    static void useWardForPatientTwo(SyncFixture f) throws Exception {
        f.source.execute("INSERT INTO location VALUES (2, 'Ward', 'location-ward-h4')",
                "UPDATE encounter SET location_id = 2 WHERE uuid = 'enc-p2'");
    }

    private static Finding find(List<Finding> findings, String uuid) {
        return findings.stream().filter(x -> x.uuid().equals(uuid)).findFirst().orElseThrow();
    }
}
