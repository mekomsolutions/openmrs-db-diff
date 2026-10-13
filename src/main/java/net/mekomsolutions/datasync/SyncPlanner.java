package net.mekomsolutions.datasync;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.mekomsolutions.datasync.IdentityResolver.Side;
import net.mekomsolutions.datasync.MissingRowFinder.MissingRow;
import net.mekomsolutions.datasync.MissingRowFinder.TableDiff;

/**
 * Decides which missing rows can be copied. A row can be copied when every foreign key it holds points at a row
 * that already exists in the target or is itself going to be copied. Rows pointing at anything else (for example
 * a concept that only exists in the source) are blocked, and so is everything that depends on a blocked row.
 */
final class SyncPlanner {

    /** A foreign key value of a missing row, translated to the identity of the row it points at. */
    record Ref(Schema.ForeignKey fk, Schema.Table refTable, String refIdentity) {}

    record PlannedRow(Schema.Table table, MissingRow row, Map<String, Ref> refs) {}

    record BlockedRow(Schema.Table table, MissingRow row, String reason) {}

    record Plan(List<Schema.Table> order, Map<Schema.Table, List<PlannedRow>> rows, List<BlockedRow> blocked,
                Set<String> cycleTables) {
        int size() {
            return rows.values().stream().mapToInt(List::size).sum();
        }
    }

    private final Schema schema;
    private final Schema targetSchema;
    private final IdentityResolver resolver;

    SyncPlanner(Schema schema, Schema targetSchema, IdentityResolver resolver) {
        this.schema = schema;
        this.targetSchema = targetSchema;
        this.resolver = resolver;
    }

    Plan plan(List<TableDiff> diffs) throws SQLException {
        Map<Schema.Table, Map<String, PlannedRow>> planned = new LinkedHashMap<>();
        List<BlockedRow> blocked = new ArrayList<>();

        for (TableDiff d : diffs) {
            Map<String, PlannedRow> rows = new LinkedHashMap<>();
            planned.put(d.table(), rows);
            for (Schema.ForeignKey fk : d.table().foreignKeys()) {
                List<Object> values = new ArrayList<>();
                for (MissingRow r : d.missing()) {
                    values.add(r.values().get(fk.column()));
                }
                resolver.prefetchIdentities(Side.SOURCE, schema.table(fk.refTable()), fk.refColumn(), values);
            }
            for (MissingRow r : d.missing()) {
                Map<String, Ref> refs = new LinkedHashMap<>();
                String problem = null;
                for (Schema.ForeignKey fk : d.table().foreignKeys()) {
                    Object v = r.values().get(fk.column());
                    if (v == null) {
                        continue;
                    }
                    Schema.Table refTable = schema.table(fk.refTable());
                    String refId = resolver.identityOf(Side.SOURCE, refTable, fk.refColumn(), v);
                    if (refId == null) {
                        problem = fk.column() + "=" + Db.key(v) + " points at a " + fk.refTable()
                                + " row that does not exist in the source database";
                        break;
                    }
                    refs.put(fk.column(), new Ref(fk, refTable, refId));
                }
                if (problem != null) {
                    blocked.add(new BlockedRow(d.table(), r, problem));
                } else {
                    rows.put(r.identity(), new PlannedRow(d.table(), r, refs));
                }
            }
        }

        // Which referenced rows are already in the target.
        Map<String, Set<String>> wanted = new HashMap<>();
        for (Map<String, PlannedRow> rows : planned.values()) {
            for (PlannedRow p : rows.values()) {
                for (Ref ref : p.refs().values()) {
                    wanted.computeIfAbsent(ref.refTable().name() + "|" + ref.fk().refColumn(), k -> new LinkedHashSet<>())
                            .add(ref.refIdentity());
                }
            }
        }
        for (Map.Entry<String, Set<String>> e : wanted.entrySet()) {
            String[] parts = e.getKey().split("\\|", 2);
            resolver.prefetchValues(Side.TARGET, schema.table(parts[0]), parts[1], e.getValue());
        }

        // Drop rows whose references cannot be satisfied, repeating until nothing changes.
        Map<Schema.Table, Map<String, String>> blockedIds = new HashMap<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<Schema.Table, Map<String, PlannedRow>> e : planned.entrySet()) {
                var it = e.getValue().values().iterator();
                while (it.hasNext()) {
                    PlannedRow p = it.next();
                    String reason = unsatisfied(p, planned, blockedIds);
                    if (reason != null) {
                        it.remove();
                        blocked.add(new BlockedRow(p.table(), p.row(), reason));
                        blockedIds.computeIfAbsent(p.table(), k -> new HashMap<>()).put(p.row().identity(), reason);
                        changed = true;
                    }
                }
            }
        }

        Map<Schema.Table, List<PlannedRow>> result = new LinkedHashMap<>();
        for (Map.Entry<Schema.Table, Map<String, PlannedRow>> e : planned.entrySet()) {
            if (!e.getValue().isEmpty()) {
                result.put(e.getKey(), new ArrayList<>(e.getValue().values()));
            }
        }
        Set<String> cycleTables = new LinkedHashSet<>();
        List<Schema.Table> order = topologicalOrder(result, cycleTables);
        return new Plan(order, result, blocked, cycleTables);
    }

    private String unsatisfied(PlannedRow p, Map<Schema.Table, Map<String, PlannedRow>> planned,
                               Map<Schema.Table, Map<String, String>> blockedIds) throws SQLException {
        if (targetSchema.table(p.table().name()) == null) {
            return "table does not exist in the target database";
        }
        for (Ref ref : p.refs().values()) {
            if (resolver.valueFor(Side.TARGET, ref.refTable(), ref.fk().refColumn(), ref.refIdentity()) != null) {
                continue;
            }
            Map<String, PlannedRow> refRows = planned.get(ref.refTable());
            if (refRows != null && refRows.containsKey(ref.refIdentity())) {
                continue;
            }
            Map<String, String> b = blockedIds.get(ref.refTable());
            if (b != null && b.containsKey(ref.refIdentity())) {
                return ref.fk().column() + " depends on a " + ref.refTable().name() + " row (" + shortId(ref.refIdentity())
                        + ") that cannot be synced";
            }
            return ref.fk().column() + " points at " + ref.refTable().name() + " " + shortId(ref.refIdentity())
                    + ", which is missing from the target"
                    + (planned.containsKey(ref.refTable()) ? "" : " and that table is not synced (excluded or metadata)");
        }
        return null;
    }

    private static String shortId(String identity) {
        return identity.replace('\u001F', '/');
    }

    /** Parents before children. Tables in a foreign-key cycle (person and users) are ordered best-effort. */
    private static List<Schema.Table> topologicalOrder(Map<Schema.Table, List<PlannedRow>> rows,
                                                       Set<String> cycleTables) {
        Map<Schema.Table, Set<Schema.Table>> deps = new LinkedHashMap<>();
        for (Schema.Table t : rows.keySet()) {
            Set<Schema.Table> d = new HashSet<>();
            for (Schema.ForeignKey fk : t.foreignKeys()) {
                for (Schema.Table other : rows.keySet()) {
                    if (other != t && other.name().equals(fk.refTable())) {
                        d.add(other);
                    }
                }
            }
            deps.put(t, d);
        }
        List<Schema.Table> order = new ArrayList<>();
        Set<Schema.Table> done = new HashSet<>();
        while (order.size() < deps.size()) {
            Schema.Table next = null;
            for (Map.Entry<Schema.Table, Set<Schema.Table>> e : deps.entrySet()) {
                if (!done.contains(e.getKey()) && done.containsAll(e.getValue())) {
                    next = e.getKey();
                    break;
                }
            }
            if (next == null) {
                // Every remaining table waits on another: break a cycle at the member with the fewest unmet
                // dependencies. Tables merely waiting on a cycle are not candidates.
                int best = Integer.MAX_VALUE;
                for (Map.Entry<Schema.Table, Set<Schema.Table>> e : deps.entrySet()) {
                    if (done.contains(e.getKey()) || !onCycle(e.getKey(), deps, done)) {
                        continue;
                    }
                    int unmet = (int) e.getValue().stream().filter(x -> !done.contains(x)).count();
                    if (unmet < best) {
                        best = unmet;
                        next = e.getKey();
                    }
                }
                cycleTables.add(next.name());
            }
            order.add(next);
            done.add(next);
        }
        return order;
    }

    /** Whether {@code start} can reach itself through dependencies that are not yet done. */
    private static boolean onCycle(Schema.Table start, Map<Schema.Table, Set<Schema.Table>> deps,
                                   Set<Schema.Table> done) {
        Set<Schema.Table> seen = new HashSet<>();
        List<Schema.Table> stack = new ArrayList<>(deps.get(start));
        while (!stack.isEmpty()) {
            Schema.Table t = stack.remove(stack.size() - 1);
            if (t == start) {
                return true;
            }
            if (done.contains(t) || !seen.add(t)) {
                continue;
            }
            stack.addAll(deps.get(t));
        }
        return false;
    }
}
