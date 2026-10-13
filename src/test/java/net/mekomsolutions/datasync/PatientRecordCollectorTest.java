package net.mekomsolutions.datasync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

class PatientRecordCollectorTest {

    @Test
    void collectsEverythingReachableFromThePersonRow() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            PatientRecord record = collector(f).collect("person-p2").orElseThrow();

            assertEquals(Map.of("person", 1, "patient", 1, "person_name", 1, "encounter", 1, "obs", 2, "orders", 1,
                    "drug_order", 1), rowsPerTable(record));
            assertEquals(new TreeSet<>(java.util.List.of("obs-p2-vitals-group", "obs-p2-weight-child")),
                    uuids(record, "obs"));
        }
    }

    @Test
    void leavesOutTheUsersWhoEnteredTheData() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            PatientRecord record = collector(f).collect("person-p2").orElseThrow();

            assertTrue(record.tables().stream().noneMatch(t -> t.name().equals("users") || t.name().equals("user_role")));
            assertEquals(new TreeSet<>(java.util.List.of("person-p2")), uuids(record, "person"),
                    "the nurse who registered the patient is a person too, but not part of the record");
        }
    }

    @Test
    void neverCollectsExcludedMetadata() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            PatientRecord record = collector(f).collect("person-p1").orElseThrow();

            assertTrue(record.tables().stream().noneMatch(t -> SyncFixture.EXCLUDED.contains(t.name())));
            assertEquals(4, record.rows().get(f.sourceSchema.table("obs")).size());
        }
    }

    @Test
    void findsNothingForUnknownUuidsOrPeopleWhoAreNotPatients() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            PatientRecordCollector collector = collector(f);

            assertEquals(Optional.empty(), collector.collect("no-such-patient"));
            assertEquals(Optional.empty(), collector.collect("person-admin"));
        }
    }

    static PatientRecordCollector collector(SyncFixture f) {
        IdentityResolver resolver = f.resolver();
        return new PatientRecordCollector(f.sourceSchema, f.links(), new SourceReader(f.databases.source(), resolver));
    }

    private static Map<String, Integer> rowsPerTable(PatientRecord record) {
        Map<String, Integer> counts = new TreeMap<>();
        record.rows().forEach((table, rows) -> counts.put(table.name(), rows.size()));
        return counts;
    }

    private static TreeSet<String> uuids(PatientRecord record, String table) {
        TreeSet<String> uuids = new TreeSet<>();
        record.rows().forEach((t, rows) -> {
            if (t.name().equals(table)) {
                rows.forEach(r -> uuids.add(r.describe().trim()));
            }
        });
        return uuids;
    }
}
