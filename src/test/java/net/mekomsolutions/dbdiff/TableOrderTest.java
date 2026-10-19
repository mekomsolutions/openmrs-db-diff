package net.mekomsolutions.dbdiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TableOrderTest {

    @Test
    void putsParentsBeforeChildren() {
        Schema schema = new Schema();
        Schema.Table obs = table(schema, "obs", "encounter");
        Schema.Table encounter = table(schema, "encounter", "patient");
        Schema.Table patient = table(schema, "patient");

        TableOrder order = TableOrder.of(List.of(obs, encounter, patient));

        assertEquals(List.of(patient, encounter, obs), order.tables());
        assertTrue(order.cycleBreaks().isEmpty());
    }

    @Test
    void breaksACycleAtOneOfItsMembersOnly() {
        Schema schema = new Schema();
        Schema.Table name = table(schema, "person_name", "person", "users");
        Schema.Table person = table(schema, "person", "users");
        Schema.Table users = table(schema, "users", "person", "users");

        TableOrder order = TableOrder.of(List.of(name, person, users));

        assertEquals(name, order.tables().get(2), "a table waiting on the cycle is never used to break it");
        assertEquals(1, order.cycleBreaks().size());
        assertTrue(Set.of("person", "users").containsAll(order.cycleBreaks()));
    }

    @Test
    void ignoresReferencesToTablesOutsideTheSet() {
        Schema schema = new Schema();
        Schema.Table obs = table(schema, "obs", "concept", "obs");

        assertEquals(List.of(obs), TableOrder.of(List.of(obs)).tables());
    }

    /** A table whose column {@code <parent>_id} points at each parent. */
    private static Schema.Table table(Schema schema, String name, String... parents) {
        Schema.Table table = schema.addTable(name);
        for (String parent : parents) {
            String column = parent + "_ref";
            table.addColumn(new Schema.Column(column, "int", true, false));
            table.addForeignKey(new Schema.ForeignKey(column, parent, parent + "_id"));
        }
        return table;
    }
}
