package net.mekomsolutions.dbdiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.mekomsolutions.dbdiff.MetadataDecisions.Action;
import net.mekomsolutions.dbdiff.PatientResult.Status;

class MetadataDecisionsTest {

    @TempDir
    Path dir;

    @Test
    void copyBringsTheMissingConceptAlongWithThePatient() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            MetadataDecisions decisions = decisions("concept,concept-height-source-only,HEIGHT,COPY,");

            PatientResult result = syncer(f, decisions, RunMode.APPLY).sync("person-p1");

            assertEquals(Status.SYNCED, result.status());
            assertEquals(0, result.blocked());
            assertEquals(Map.of("concept", 1, "obs", 3), result.inserted());
            assertEquals("concept-height-source-only", f.targetValue(
                    "SELECT c.uuid FROM obs o JOIN concept c ON c.concept_id = o.concept_id WHERE o.uuid = 'obs-p1-height'"));
        }
    }

    @Test
    void mapPointsCopiedRowsAtTheTargetsOwnRow() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            MetadataCheckTest.useWardForPatientTwo(f);
            f.target.execute("INSERT INTO location VALUES (7, 'Ward', 'location-ward-h9')");
            MetadataDecisions decisions = decisions("location,location-ward-h4,Ward,MAP,location-ward-h9");

            PatientResult result = syncer(f, decisions, RunMode.APPLY).sync("person-p2");

            assertEquals(Status.SYNCED, result.status());
            assertEquals("location-ward-h9", f.targetValue(
                    "SELECT l.uuid FROM encounter e JOIN location l ON l.location_id = e.location_id WHERE e.uuid = 'enc-p2'"));
            assertEquals(1, f.target.count("location WHERE name = 'Ward'"));
        }
    }

    @Test
    void copiesOnlyFromListedMetadataTables() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            SyncConfig conceptsNotListed = new SyncConfig(f.config.source(), f.config.target(),
                    f.config.excludedTables(), f.config.outputDir(), Set.of("location"), null);
            MetadataDecisions decisions = decisions("concept,concept-height-source-only,HEIGHT,COPY,");

            PatientResult result = PatientSyncer.create(f.databases, f.sourceSchema, f.targetSchema,
                    conceptsNotListed, decisions, RunMode.DRY_RUN).sync("person-p1");

            assertEquals(2, result.blocked());
        }
    }

    @Test
    void withoutADecisionTheRowsStayBlockedAndSaySo() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            PatientResult result = syncer(f, MetadataDecisions.none(), RunMode.DRY_RUN).sync("person-p1");

            assertEquals(2, result.blocked());
            assertTrue(result.blockedRows().get(0).reason().contains("metadata decisions"),
                    result.blockedRows().get(0).reason());
        }
    }

    @Test
    void aMapToARowTheTargetDoesNotHaveIsBlockedWithTheReason() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            MetadataDecisions decisions = decisions("concept,concept-height-source-only,HEIGHT,MAP,no-such-concept");

            PatientResult result = syncer(f, decisions, RunMode.DRY_RUN).sync("person-p1");

            assertEquals(2, result.blocked());
            assertTrue(result.blockedRows().get(0).reason().contains("is mapped to concept no-such-concept"),
                    result.blockedRows().get(0).reason());
        }
    }

    @Test
    void readsActionsCaseInsensitivelyAndTreatsUnlistedRowsAsSkip() throws Exception {
        MetadataDecisions decisions = decisions("Location,LOCATION-WARD,\"Ward, east\",map,location-x");

        assertEquals(Action.MAP, decisions.action("location", "location-ward"));
        assertEquals(Action.SKIP, decisions.action("location", "location-other"));
    }

    @Test
    void rejectsAMapWithoutATargetAndUnknownActions() {
        IllegalArgumentException noTarget = assertThrows(IllegalArgumentException.class,
                () -> decisions("location,location-ward,Ward,MAP,"));
        assertTrue(noTarget.getMessage().endsWith("line 2: MAP needs a target_uuid"), noTarget.getMessage());
        IllegalArgumentException badAction = assertThrows(IllegalArgumentException.class,
                () -> decisions("location,location-ward,Ward,MERGE,"));
        assertTrue(badAction.getMessage().contains("action must be MAP, COPY or SKIP"), badAction.getMessage());
    }

    @Test
    void noFileMeansNoDecisions() throws Exception {
        assertSame(MetadataDecisions.none(), MetadataDecisions.load(dir.resolve("missing.csv")));
        assertSame(MetadataDecisions.none(), MetadataDecisions.load(null));
    }

    private MetadataDecisions decisions(String... lines) throws Exception {
        Path file = dir.resolve("metadata-decisions.csv");
        Files.writeString(file, "table,source_uuid,name,action,target_uuid\n" + String.join("\n", lines) + "\n");
        return MetadataDecisions.load(file);
    }

    private static PatientSyncer syncer(SyncFixture f, MetadataDecisions decisions, RunMode mode) {
        return PatientSyncer.create(f.databases, f.sourceSchema, f.targetSchema, f.config, decisions, mode);
    }
}
