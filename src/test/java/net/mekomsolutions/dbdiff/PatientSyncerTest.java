package net.mekomsolutions.dbdiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.mekomsolutions.dbdiff.PatientResult.Status;

class PatientSyncerTest {

    @Test
    void reportFindsMissingRowsWithoutWriting() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            PatientResult result = f.syncer(RunMode.REPORT).sync("person-p2");

            assertEquals(Status.MISSING, result.status());
            assertEquals(11, result.missing());
            assertEquals(11, result.syncable());
            assertEquals(0, result.insertedTotal());
            assertEquals(4, f.target.count("person"));
        }
    }

    @Test
    void dryRunInsertsThenRollsBack() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            PatientResult result = f.syncer(RunMode.DRY_RUN).sync("person-p2");

            assertEquals(Status.DRY_RUN_OK, result.status());
            assertEquals(11, result.insertedTotal());
            assertEquals(4, f.target.count("person"));
            assertEquals(1, f.target.count("obs"));
        }
    }

    @Test
    void applyCopiesThePatientAndRemapsEveryId() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            PatientResult result = f.syncer(RunMode.APPLY).sync("person-p2");

            assertEquals(Status.SYNCED, result.status());
            assertEquals(11, result.insertedTotal());
            assertEquals("person-p2", f.targetValue(
                    "SELECT p.uuid FROM patient pa JOIN person p ON p.person_id = pa.patient_id WHERE p.uuid = 'person-p2'"));
            assertEquals("user-nurse", f.targetValue(
                    "SELECT u.uuid FROM person p JOIN users u ON u.user_id = p.creator WHERE p.uuid = 'person-p2'"));
            assertEquals("user-nurse", f.targetValue(
                    "SELECT c.uuid FROM users u JOIN users c ON c.user_id = u.creator WHERE u.uuid = 'user-nurse'"));
            assertEquals("concept-weight", f.targetValue(
                    "SELECT c.uuid FROM obs o JOIN concept c ON c.concept_id = o.concept_id WHERE o.uuid = 'obs-p2-weight-child'"));
            assertEquals("obs-p2-vitals-group", f.targetValue(
                    "SELECT g.uuid FROM obs o JOIN obs g ON g.obs_id = o.obs_group_id WHERE o.uuid = 'obs-p2-weight-child'"));
            assertEquals("order-p2", f.targetValue(
                    "SELECT o.uuid FROM drug_order d JOIN orders o ON o.order_id = d.order_id"));
        }
    }

    @Test
    void secondApplyFindsNothingLeft() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            PatientSyncer syncer = f.syncer(RunMode.APPLY);
            syncer.sync("person-p2");

            PatientResult again = syncer.sync("person-p2");

            assertEquals(Status.IN_SYNC, again.status());
            assertEquals(0, again.missing());
        }
    }

    @Test
    void patientWithOnlyBlockedRowsLeftIsBlocked() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            PatientSyncer syncer = f.syncer(RunMode.APPLY);
            assertEquals(Status.SYNCED, syncer.sync("person-p1").status());

            PatientResult again = syncer.sync("person-p1");

            assertEquals(Status.BLOCKED, again.status());
            assertEquals(2, again.blocked());
            assertEquals(0, f.target.count("obs WHERE uuid IN ('obs-p1-height', 'obs-p1-child-of-height')"));
        }
    }

    @Test
    void usersCopiedForOnePatientAreReusedByTheNext() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            PatientSyncer syncer = f.syncer(RunMode.APPLY);
            syncer.sync("person-p2");
            f.source.execute(
                    "INSERT INTO person VALUES (7, 'F', NULL, 2, '2022-07-07 07:00:00', 0, 'person-p4')",
                    "INSERT INTO patient VALUES (7, 2, '2022-07-07 07:00:00', 0)");

            PatientResult next = syncer.sync("person-p4");

            assertEquals(Status.SYNCED, next.status());
            assertEquals(2, next.insertedTotal(), "only person and patient; the nurse is already there");
            assertEquals(1, f.target.count("users WHERE uuid = 'user-nurse'"));
        }
    }

    @Test
    void failedPatientLeavesNothingBehindAndTheNextOneStillSyncs() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            PatientSyncer syncer = f.syncer(RunMode.APPLY);

            PatientResult failed = syncer.sync("person-p3");
            PatientResult next = syncer.sync("person-p2");

            assertEquals(Status.FAILED, failed.status());
            assertTrue(failed.error().contains("users"), failed.error());
            assertNotNull(failed.plan(), "the plan is kept for the report");
            assertEquals(0, f.target.count("person WHERE uuid IN ('person-p3', 'person-clerk')"));
            assertEquals(0, f.target.count("users WHERE uuid = 'user-clerk'"));
            assertEquals(Status.SYNCED, next.status());
        }
    }

    @Test
    void unknownPatientIsNotFound() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            assertEquals(Status.NOT_FOUND, f.syncer(RunMode.APPLY).sync("no-such-patient").status());
        }
    }

    @Test
    void rowAlreadyInTheTargetUnderAnotherPatientStopsThePatient() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            // the target holds an encounter with p2's encounter uuid, but it belongs to another person
            f.target.execute(
                    "INSERT INTO patient VALUES (2, 1, '2021-01-01 00:00:00', 0)",
                    "INSERT INTO encounter VALUES (2, 1, 2, 1, '2021-01-01 00:00:00', 1, '2021-01-01 00:00:00', 'enc-p2')");

            PatientResult result = f.syncer(RunMode.APPLY).sync("person-p2");

            assertEquals(Status.CONFLICT, result.status());
            assertEquals(1, result.conflictCount());
            OwnershipCheck.Conflict conflict = result.conflicts().get(0);
            assertEquals("encounter", conflict.table().name());
            assertEquals("patient_id", conflict.fk().column());
            assertEquals("person-p2", conflict.sourceParent());
            assertEquals("person-only-in-target", conflict.targetParent());
            assertEquals(0, result.insertedTotal());
            assertEquals(4, f.target.count("person"));
            assertEquals(1, f.target.count("obs"));
        }
    }

    @Test
    void rowsAttachedToTheSameParentsAreNoConflict() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            PatientSyncer syncer = f.syncer(RunMode.APPLY);
            syncer.sync("person-p2");

            PatientResult again = syncer.sync("person-p2");

            assertEquals(Status.IN_SYNC, again.status());
            assertEquals(0, again.conflictCount());
        }
    }

    @Test
    void placeholderRowIsWrittenOverWithTheSourceRow() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            // a merge parked p2's encounter on the placeholder person, with made-up values
            f.target.execute(
                    "INSERT INTO person VALUES (9, NULL, NULL, 1, '1970-01-01 00:00:00', 1, '[PHANTOM_UUID]')",
                    "INSERT INTO patient VALUES (9, 1, '1970-01-01 00:00:00', 1)",
                    "INSERT INTO encounter VALUES (7, 1, 9, NULL, '1970-01-01 00:00:00', 1, '1970-01-01 00:00:00', 'enc-p2')");

            PatientResult result = f.syncer(RunMode.APPLY).sync("person-p2");

            assertEquals(Status.SYNCED, result.status());
            assertEquals(0, result.conflictCount());
            assertEquals(1, result.placeholders());
            assertEquals("7", f.targetValue("SELECT encounter_id FROM encounter WHERE uuid = 'enc-p2'"));
            assertEquals("person-p2", f.targetValue(
                    "SELECT p.uuid FROM encounter e JOIN person p ON p.person_id = e.patient_id WHERE e.uuid = 'enc-p2'"));
            assertEquals("location-clinic", f.targetValue(
                    "SELECT l.uuid FROM encounter e JOIN location l ON l.location_id = e.location_id WHERE e.uuid = 'enc-p2'"));
            assertEquals("2022-03-03 09:20:00", f.targetValue(
                    "SELECT FORMATDATETIME(encounter_datetime, 'yyyy-MM-dd HH:mm:ss') FROM encounter WHERE uuid = 'enc-p2'"));
            assertEquals("enc-p2", f.targetValue(
                    "SELECT e.uuid FROM obs o JOIN encounter e ON e.encounter_id = o.encounter_id WHERE o.uuid = 'obs-p2-weight-child'"));
        }
    }

    @Test
    void reportCountsPlaceholdersWithoutWriting() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            f.target.execute(
                    "INSERT INTO person VALUES (9, NULL, NULL, 1, '1970-01-01 00:00:00', 1, '[PHANTOM_UUID]')",
                    "INSERT INTO patient VALUES (9, 1, '1970-01-01 00:00:00', 1)",
                    "INSERT INTO encounter VALUES (7, 1, 9, NULL, '1970-01-01 00:00:00', 1, '1970-01-01 00:00:00', 'enc-p2')");

            PatientResult result = f.syncer(RunMode.REPORT).sync("person-p2");

            assertEquals(Status.MISSING, result.status());
            assertEquals(1, result.placeholders());
            assertEquals("9", f.targetValue("SELECT patient_id FROM encounter WHERE uuid = 'enc-p2'"));
        }
    }

    @Test
    void rowOnAPlaceholderLocationIsWrittenOverToo() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            // p2 and its encounter are in the target, but the merge did not know the encounter's location
            f.target.execute(
                    "INSERT INTO person VALUES (10, 'F', '2000-12-31', 1, '2022-03-03 09:15:00', 0, 'person-p2')",
                    "INSERT INTO patient VALUES (10, 1, '2022-03-03 09:15:00', 0)",
                    "INSERT INTO location VALUES (5, 'Unknown', '[PHANTOM_UUID]')",
                    "INSERT INTO encounter VALUES (8, 1, 10, 5, '1970-01-01 00:00:00', 1, '1970-01-01 00:00:00', 'enc-p2')");

            PatientResult result = f.syncer(RunMode.APPLY).sync("person-p2");

            assertEquals(Status.SYNCED, result.status());
            assertEquals(1, result.placeholders());
            assertEquals("8", f.targetValue("SELECT encounter_id FROM encounter WHERE uuid = 'enc-p2'"));
            assertEquals("location-clinic", f.targetValue(
                    "SELECT l.uuid FROM encounter e JOIN location l ON l.location_id = e.location_id WHERE e.uuid = 'enc-p2'"));
        }
    }
}
