package net.mekomsolutions.datasync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class SyncPlannerTest {

    @Test
    void blocksRowsOnMissingMetadataAndEverythingThatDependsOnThem() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            SyncPlan plan = plan(f, "person-p1");

            assertEquals(Set.of("obs-p1-weight-2"), plannedUuids(plan, "obs"));
            Map<String, String> reasons = plan.blocked().stream()
                    .collect(Collectors.toMap(b -> b.row().describe().trim(), SyncPlan.BlockedRow::reason));
            assertTrue(reasons.get("obs-p1-height").startsWith("concept_id points at concept"));
            assertTrue(reasons.get("obs-p1-height").contains("not synced"));
            assertTrue(reasons.get("obs-p1-child-of-height").startsWith("obs_group_id depends on obs"));
        }
    }

    @Test
    void pullsInMissingUsersWithTheirPersonAndRoles() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            SyncPlan plan = plan(f, "person-p2");

            assertEquals(Set.of("user-nurse"), plannedUuids(plan, "users"));
            assertEquals(Set.of("person-p2", "person-user2"), plannedUuids(plan, "person"));
            assertEquals(1, plan.rows().get(f.sourceSchema.table("user_role")).size());
            assertTrue(plan.blocked().isEmpty());
            assertEquals(11, plan.size());
        }
    }

    @Test
    void ordersTablesParentsFirstAndReportsTheCycleItBroke() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            SyncPlan plan = plan(f, "person-p2");
            List<String> order = plan.order().stream().map(Schema.Table::name).toList();

            assertTrue(order.indexOf("patient") < order.indexOf("encounter"));
            assertTrue(order.indexOf("encounter") < order.indexOf("obs"));
            assertTrue(order.indexOf("orders") < order.indexOf("drug_order"));
            assertTrue(order.indexOf("users") < order.indexOf("user_role"));
            assertEquals(1, plan.cycleTables().size());
            assertTrue(Set.of("person", "users").containsAll(plan.cycleTables()));
        }
    }

    @Test
    void plansNothingForRowsTheTargetAlreadyHas() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            SyncPlan plan = plan(f, "person-p1");

            assertFalse(plan.rows().containsKey(f.sourceSchema.table("person")));
            assertFalse(plan.rows().containsKey(f.sourceSchema.table("encounter")));
        }
    }

    /** Collects the patient, keeps what the target lacks and plans it, as {@link PatientSyncer} does. */
    static SyncPlan plan(SyncFixture f, String patientUuid) throws Exception {
        IdentityResolver resolver = f.resolver();
        SourceReader reader = new SourceReader(f.databases.source(), resolver);
        PatientRecord record = new PatientRecordCollector(f.sourceSchema, f.links(), reader)
                .collect(patientUuid).orElseThrow();
        Map<Schema.Table, List<SourceRow>> missing = PatientSyncer.missingFromTarget(resolver, record);
        return new SyncPlanner(f.sourceSchema, f.scope(), f.links(), reader, resolver).plan(missing);
    }

    private static Set<String> plannedUuids(SyncPlan plan, String table) {
        Set<String> uuids = new TreeSet<>();
        plan.rows().forEach((t, rows) -> {
            if (t.name().equals(table)) {
                rows.forEach(r -> uuids.add(r.row().describe().trim()));
            }
        });
        return uuids;
    }
}
