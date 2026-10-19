package net.mekomsolutions.dbdiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.mekomsolutions.dbdiff.IdentityResolver.Side;

class IdentityResolverTest {

    @Test
    void matchesRowsByUuidAcrossDifferentIds() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            IdentityResolver resolver = f.resolver();
            Schema.Table person = f.sourceSchema.table("person");

            assertEquals("person-p1", resolver.identityOf(Side.SOURCE, person, "person_id", 2));
            assertEquals(3, ((Number) resolver.valueFor(Side.TARGET, person, "person_id", "person-p1")).intValue());
            assertNull(resolver.valueFor(Side.TARGET, person, "person_id", "person-p2"));
        }
    }

    @Test
    void matchesRowsWithoutUuidThroughTheRowsTheyPointAt() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            IdentityResolver resolver = f.resolver();
            Schema.Table patient = f.sourceSchema.table("patient");

            // patient 2 in the source and patient 3 in the target are both person-p1
            String identity = resolver.identityOf(Side.SOURCE, patient, "patient_id", 2);
            assertEquals("person-p1", identity);
            assertEquals(Set.of("person-p1"), resolver.existing(Side.TARGET, patient, List.of("person-p1", "person-p2")));
        }
    }

    @Test
    void pendingInsertsCountUntilDiscarded() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            IdentityResolver resolver = f.resolver();
            Schema.Table person = f.sourceSchema.table("person");

            resolver.recordInsert(person, "person-p2", Map.of("person_id", 99));
            assertEquals(99, resolver.valueFor(Side.TARGET, person, "person_id", "person-p2"));
            assertEquals(Set.of("person-p2"), resolver.existing(Side.TARGET, person, List.of("person-p2")));

            resolver.discardInserts();
            assertNull(resolver.valueFor(Side.TARGET, person, "person_id", "person-p2"));
        }
    }

    @Test
    void committedInsertsAreSeenInTheTargetItself() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            IdentityResolver resolver = f.resolver();
            Schema.Table person = f.sourceSchema.table("person");
            assertNull(resolver.valueFor(Side.TARGET, person, "person_id", "person-p2")); // cached as missing

            f.target.execute("INSERT INTO person VALUES (50, 'F', NULL, 1, '2022-03-03 09:15:00', 0, 'person-p2')");
            resolver.recordInsert(person, "person-p2", Map.of("person_id", 50));
            resolver.commitInserts();

            assertEquals(50, ((Number) resolver.valueFor(Side.TARGET, person, "person_id", "person-p2")).intValue());
        }
    }
}
