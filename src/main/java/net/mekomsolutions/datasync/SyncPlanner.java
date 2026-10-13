package net.mekomsolutions.datasync;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.mekomsolutions.datasync.IdentityResolver.Side;
import net.mekomsolutions.datasync.SyncPlan.BlockedRow;
import net.mekomsolutions.datasync.SyncPlan.PlannedRow;
import net.mekomsolutions.datasync.SyncPlan.Ref;

/**
 * Decides which of a patient's missing rows can be copied.
 *
 * <p>A row can be copied when every foreign key it holds points at a row that is already in the target or is
 * copied along with it. Rows the patient's data points at that the target lacks (the user who registered the
 * patient, that user's person row, a provider, ...) are pulled in from the source and planned too, as long as their
 * table is in scope. Anything else, such as an obs on a concept the target does not have, is blocked, and so is
 * every row that depends on a blocked row.
 */
final class SyncPlanner {

    private final Schema source;
    private final SyncScope scope;
    private final TableLinks links;
    private final SourceReader reader;
    private final IdentityResolver resolver;

    SyncPlanner(Schema source, SyncScope scope, TableLinks links, SourceReader reader, IdentityResolver resolver) {
        this.source = source;
        this.scope = scope;
        this.links = links;
        this.reader = reader;
        this.resolver = resolver;
    }

    SyncPlan plan(Map<Schema.Table, List<SourceRow>> missing) throws SQLException {
        Draft draft = new Draft();
        Map<Schema.Table, List<SourceRow>> next = missing;
        while (!next.isEmpty()) {
            List<PlannedRow> added = addRows(draft, next);
            next = missingParents(draft, added);
        }
        dropUnsatisfiable(draft);
        return draft.toPlan();
    }

    /** Plans rows with their foreign keys resolved; a row pointing at nothing, even in the source, is blocked. */
    private List<PlannedRow> addRows(Draft draft, Map<Schema.Table, List<SourceRow>> rowsByTable) throws SQLException {
        List<PlannedRow> added = new ArrayList<>();
        for (Map.Entry<Schema.Table, List<SourceRow>> e : rowsByTable.entrySet()) {
            Schema.Table table = e.getKey();
            for (Schema.ForeignKey fk : table.foreignKeys()) {
                resolver.prefetchIdentities(Side.SOURCE, source.table(fk.refTable()), fk.refColumn(),
                        SourceReader.values(e.getValue(), fk.column()));
            }
            for (SourceRow row : e.getValue()) {
                if (draft.contains(table, row.identity())) {
                    continue;
                }
                Map<String, Ref> refs = new LinkedHashMap<>();
                String problem = null;
                for (Schema.ForeignKey fk : table.foreignKeys()) {
                    Object value = row.values().get(fk.column());
                    if (value == null) {
                        continue;
                    }
                    Schema.Table refTable = source.table(fk.refTable());
                    String refIdentity = resolver.identityOf(Side.SOURCE, refTable, fk.refColumn(), value);
                    if (refIdentity == null) {
                        problem = fk.column() + "=" + Db.key(value) + " points at a " + fk.refTable()
                                + " row that does not exist in the source database";
                        break;
                    }
                    refs.put(fk.column(), new Ref(fk, refTable, value, refIdentity));
                }
                if (problem != null) {
                    draft.block(new BlockedRow(table, row, problem));
                } else {
                    PlannedRow planned = new PlannedRow(table, row, refs);
                    draft.add(planned);
                    added.add(planned);
                }
            }
        }
        return added;
    }

    /**
     * Source rows that {@code added} points at which are neither in the target nor planned yet, together with
     * their extension rows. References into tables outside the scope are left for {@link #dropUnsatisfiable}.
     */
    private Map<Schema.Table, List<SourceRow>> missingParents(Draft draft, List<PlannedRow> added)
            throws SQLException {
        prefetchTargetValues(added);
        Map<Schema.Table, Map<String, Set<Object>>> wanted = new LinkedHashMap<>();
        for (PlannedRow row : added) {
            for (Ref ref : row.refs().values()) {
                if (scope.includes(ref.refTable()) && !draft.contains(ref.refTable(), ref.refIdentity())
                        && !inTarget(ref)) {
                    wanted.computeIfAbsent(ref.refTable(), t -> new LinkedHashMap<>())
                            .computeIfAbsent(ref.fk().refColumn(), c -> new LinkedHashSet<>())
                            .add(ref.sourceValue());
                }
            }
        }
        Map<Schema.Table, List<SourceRow>> parents = new LinkedHashMap<>();
        for (Map.Entry<Schema.Table, Map<String, Set<Object>>> byTable : wanted.entrySet()) {
            for (Map.Entry<String, Set<Object>> byColumn : byTable.getValue().entrySet()) {
                List<SourceRow> rows = reader.rowsWhere(byTable.getKey(), byColumn.getKey(), byColumn.getValue());
                addWithExtensions(parents, byTable.getKey(), rows);
            }
        }
        return parents;
    }

    /** Adds {@code rows} and, recursively, the rows extending them that the target does not have. */
    private void addWithExtensions(Map<Schema.Table, List<SourceRow>> into, Schema.Table table, List<SourceRow> rows)
            throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        into.computeIfAbsent(table, t -> new ArrayList<>()).addAll(rows);
        for (TableLinks.Link link : links.extensionsOf(table)) {
            List<Object> keys = SourceReader.values(rows, link.fk().refColumn());
            if (keys.isEmpty()) {
                continue;
            }
            List<SourceRow> extensions = reader.rowsWhere(link.child(), link.fk().column(), keys);
            Set<String> present = resolver.existing(Side.TARGET, link.child(),
                    extensions.stream().map(SourceRow::identity).toList());
            addWithExtensions(into, link.child(),
                    extensions.stream().filter(r -> !present.contains(r.identity())).toList());
        }
    }

    /** Looks up, in a few queries, the target values behind every reference of {@code rows}. */
    private void prefetchTargetValues(List<PlannedRow> rows) throws SQLException {
        Map<Schema.Table, Map<String, Set<String>>> wanted = new LinkedHashMap<>();
        for (PlannedRow row : rows) {
            for (Ref ref : row.refs().values()) {
                wanted.computeIfAbsent(ref.refTable(), t -> new LinkedHashMap<>())
                        .computeIfAbsent(ref.fk().refColumn(), c -> new LinkedHashSet<>())
                        .add(ref.refIdentity());
            }
        }
        for (Map.Entry<Schema.Table, Map<String, Set<String>>> byTable : wanted.entrySet()) {
            for (Map.Entry<String, Set<String>> byColumn : byTable.getValue().entrySet()) {
                resolver.prefetchValues(Side.TARGET, byTable.getKey(), byColumn.getKey(), byColumn.getValue());
            }
        }
    }

    private boolean inTarget(Ref ref) throws SQLException {
        return resolver.valueFor(Side.TARGET, ref.refTable(), ref.fk().refColumn(), ref.refIdentity()) != null;
    }

    /** Blocks rows whose references cannot be satisfied, repeating until nothing changes. */
    private void dropUnsatisfiable(Draft draft) throws SQLException {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map<String, PlannedRow> rows : draft.planned.values()) {
                var it = rows.values().iterator();
                while (it.hasNext()) {
                    PlannedRow row = it.next();
                    String reason = unsatisfied(row, draft);
                    if (reason != null) {
                        it.remove();
                        draft.block(new BlockedRow(row.table(), row.row(), reason));
                        changed = true;
                    }
                }
            }
        }
    }

    private String unsatisfied(PlannedRow row, Draft draft) throws SQLException {
        for (Ref ref : row.refs().values()) {
            if (inTarget(ref) || draft.isPlanned(ref.refTable(), ref.refIdentity())) {
                continue;
            }
            String parent = ref.refTable().name() + " " + IdentityResolver.readable(ref.refIdentity());
            if (draft.isBlocked(ref.refTable(), ref.refIdentity())) {
                return ref.fk().column() + " depends on " + parent + ", which cannot be synced";
            }
            return ref.fk().column() + " points at " + parent + ", which is missing from the target"
                    + (scope.includes(ref.refTable()) ? "" : " and that table is not synced (excluded or metadata)");
        }
        return null;
    }

    /** The plan being built: planned and blocked rows by table and identity. */
    private static final class Draft {
        final Map<Schema.Table, Map<String, PlannedRow>> planned = new LinkedHashMap<>();
        final Map<Schema.Table, Map<String, BlockedRow>> blocked = new HashMap<>();
        final List<BlockedRow> blockedInOrder = new ArrayList<>();

        void add(PlannedRow row) {
            planned.computeIfAbsent(row.table(), t -> new LinkedHashMap<>()).put(row.row().identity(), row);
        }

        void block(BlockedRow row) {
            blocked.computeIfAbsent(row.table(), t -> new HashMap<>()).put(row.row().identity(), row);
            blockedInOrder.add(row);
        }

        boolean isPlanned(Schema.Table table, String identity) {
            return planned.getOrDefault(table, Map.of()).containsKey(identity);
        }

        boolean isBlocked(Schema.Table table, String identity) {
            return blocked.getOrDefault(table, Map.of()).containsKey(identity);
        }

        boolean contains(Schema.Table table, String identity) {
            return isPlanned(table, identity) || isBlocked(table, identity);
        }

        SyncPlan toPlan() {
            Map<Schema.Table, List<PlannedRow>> rows = new LinkedHashMap<>();
            planned.forEach((table, byIdentity) -> {
                if (!byIdentity.isEmpty()) {
                    List<PlannedRow> inOrder = new ArrayList<>(byIdentity.values());
                    inOrder.sort(byPrimaryKey(table));
                    rows.put(table, inOrder);
                }
            });
            TableOrder order = TableOrder.of(rows.keySet());
            return new SyncPlan(order.tables(), rows, blockedInOrder, order.cycleBreaks());
        }

        /** Source primary key order, so parents usually come before the children pointing at them (obs groups). */
        private static Comparator<PlannedRow> byPrimaryKey(Schema.Table table) {
            return (r1, r2) -> {
                for (String col : table.primaryKey()) {
                    Object a = r1.row().values().get(col);
                    Object b = r2.row().values().get(col);
                    int c = a instanceof Number na && b instanceof Number nb
                            ? Long.compare(na.longValue(), nb.longValue())
                            : String.valueOf(a).compareTo(String.valueOf(b));
                    if (c != 0) {
                        return c;
                    }
                }
                return 0;
            };
        }
    }
}
