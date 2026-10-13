package net.mekomsolutions.datasync;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Insert order for a set of tables: every table after the tables it points at. Tables in a foreign-key cycle
 * (person and users point at each other) cannot all come first; the cycle is broken at the member with the fewest
 * unmet dependencies, which is then listed in {@code cycleBreaks}.
 */
record TableOrder(List<Schema.Table> tables, Set<String> cycleBreaks) {

    static TableOrder of(Collection<Schema.Table> tables) {
        Map<Schema.Table, Set<Schema.Table>> deps = dependencies(tables);
        List<Schema.Table> order = new ArrayList<>();
        Set<String> cycleBreaks = new LinkedHashSet<>();
        Set<Schema.Table> done = new HashSet<>();
        while (order.size() < deps.size()) {
            Schema.Table next = firstReady(deps, done);
            if (next == null) {
                next = cycleBreak(deps, done);
                cycleBreaks.add(next.name());
            }
            order.add(next);
            done.add(next);
        }
        return new TableOrder(order, cycleBreaks);
    }

    /** For each table, the other tables of the set that it points at. */
    private static Map<Schema.Table, Set<Schema.Table>> dependencies(Collection<Schema.Table> tables) {
        Map<Schema.Table, Set<Schema.Table>> deps = new LinkedHashMap<>();
        for (Schema.Table table : tables) {
            Set<Schema.Table> parents = new HashSet<>();
            for (Schema.ForeignKey fk : table.foreignKeys()) {
                for (Schema.Table other : tables) {
                    if (other != table && other.name().equals(fk.refTable())) {
                        parents.add(other);
                    }
                }
            }
            deps.put(table, parents);
        }
        return deps;
    }

    private static Schema.Table firstReady(Map<Schema.Table, Set<Schema.Table>> deps, Set<Schema.Table> done) {
        for (Map.Entry<Schema.Table, Set<Schema.Table>> e : deps.entrySet()) {
            if (!done.contains(e.getKey()) && done.containsAll(e.getValue())) {
                return e.getKey();
            }
        }
        return null;
    }

    /** The cycle member with the fewest unmet dependencies. Tables merely waiting on a cycle are not candidates. */
    private static Schema.Table cycleBreak(Map<Schema.Table, Set<Schema.Table>> deps, Set<Schema.Table> done) {
        Schema.Table best = null;
        long fewest = Long.MAX_VALUE;
        for (Map.Entry<Schema.Table, Set<Schema.Table>> e : deps.entrySet()) {
            if (done.contains(e.getKey()) || !onCycle(e.getKey(), deps, done)) {
                continue;
            }
            long unmet = e.getValue().stream().filter(t -> !done.contains(t)).count();
            if (unmet < fewest) {
                fewest = unmet;
                best = e.getKey();
            }
        }
        return best;
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
