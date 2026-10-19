package net.mekomsolutions.dbdiff;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

class TableLinksTest {

    @Test
    void ownershipFollowsPatientDataButNeverUsersOrExcludedTables() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            TableLinks links = f.links();

            assertEquals(Set.of("patient.patient_id", "person_name.person_id", "obs.person_id"),
                    names(links.ownedBy(f.sourceSchema.table("person"))));
            assertEquals(Set.of("encounter.patient_id", "orders.patient_id"),
                    names(links.ownedBy(f.sourceSchema.table("patient"))));
            assertEquals(Set.of(), names(links.ownedBy(f.sourceSchema.table("users"))),
                    "users are shared between patients, so they own nothing");
        }
    }

    @Test
    void extensionsAreRowsWhosePrimaryKeyPointsAtTheParent() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            TableLinks links = f.links();

            assertEquals(Set.of("patient.patient_id"), names(links.extensionsOf(f.sourceSchema.table("person"))));
            assertEquals(Set.of("user_role.user_id"), names(links.extensionsOf(f.sourceSchema.table("users"))));
            assertEquals(Set.of("drug_order.order_id"), names(links.extensionsOf(f.sourceSchema.table("orders"))));
            assertEquals(Set.of(), names(links.extensionsOf(f.sourceSchema.table("encounter"))));
        }
    }

    private static Set<String> names(List<TableLinks.Link> links) {
        Set<String> names = new TreeSet<>();
        links.forEach(l -> names.add(l.child().name() + "." + l.fk().column()));
        return names;
    }
}
