package net.mekomsolutions.dbdiff;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What to copy for one patient, and what cannot be copied.
 *
 * @param order tables in insert order, parents first
 * @param rows rows to insert, or to write over a placeholder row, by table
 * @param blocked missing rows that cannot be inserted, with the reason
 * @param cycleTables tables where a foreign-key cycle was broken; their references are fixed after the insert
 */
record SyncPlan(List<Schema.Table> order, Map<Schema.Table, List<PlannedRow>> rows, List<BlockedRow> blocked,
                Set<String> cycleTables) {

    /** A foreign key value of a source row, with the identity of the row it points at. */
    record Ref(Schema.ForeignKey fk, Schema.Table refTable, Object sourceValue, String refIdentity) {}

    /**
     * A row to copy.
     *
     * @param replace true when the target already has the row as a placeholder (see {@link OwnershipCheck}): it is
     *                updated in place, keeping its id, instead of inserted
     */
    record PlannedRow(Schema.Table table, SourceRow row, Map<String, Ref> refs, boolean replace) {

        PlannedRow(Schema.Table table, SourceRow row, Map<String, Ref> refs) {
            this(table, row, refs, false);
        }
    }

    record BlockedRow(Schema.Table table, SourceRow row, String reason) {}

    /** Number of rows to write, inserted or replacing a placeholder. */
    int size() {
        return rows.values().stream().mapToInt(List::size).sum();
    }

    /** Number of placeholder rows to write over. */
    int replacements() {
        return (int) rows.values().stream().flatMap(List::stream).filter(PlannedRow::replace).count();
    }

    /** Number of rows missing from the target, whether they can be inserted or not. */
    int missing() {
        return size() + blocked.size();
    }
}
