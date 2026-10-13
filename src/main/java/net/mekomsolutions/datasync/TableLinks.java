package net.mekomsolutions.datasync;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The foreign keys between tables in scope, seen from the referenced (parent) table. They answer two questions:
 *
 * <ul>
 *   <li>{@link #ownedBy}: which rows belong to a row? Following these from a person collects the patient's whole
 *       record: names, the patient row, encounters, their obs, orders, visits, and so on.</li>
 *   <li>{@link #extensionsOf}: which rows extend a row? An extension row's primary key is a foreign key to its
 *       parent (patient extends person, drug_order extends orders, user_role extends users), so it is only ever
 *       copied together with that parent.</li>
 * </ul>
 */
final class TableLinks {

    /** A foreign key, together with the table that holds it. */
    record Link(Schema.Table child, Schema.ForeignKey fk) {}

    /**
     * Rows of these tables are shared between patients: they say who recorded the data, not whose data it is.
     * Ownership never passes through them, otherwise one patient's record would grow to everything its creator
     * ever entered.
     */
    private static final Set<String> SHARED_TABLES = Set.of("users", "provider");

    private final Map<String, List<Link>> ownedBy = new HashMap<>();
    private final Map<String, List<Link>> extensionsOf = new HashMap<>();

    TableLinks(Schema source, SyncScope scope) {
        for (Schema.Table child : source.tables()) {
            if (!scope.includes(child)) {
                continue;
            }
            for (Schema.ForeignKey fk : child.foreignKeys()) {
                Link link = new Link(child, fk);
                if (!SHARED_TABLES.contains(child.name())) {
                    ownedBy.computeIfAbsent(fk.refTable(), k -> new ArrayList<>()).add(link);
                }
                if (child.primaryKey().contains(fk.column())) {
                    extensionsOf.computeIfAbsent(fk.refTable(), k -> new ArrayList<>()).add(link);
                }
            }
        }
    }

    /** Foreign keys through which rows of other tables belong to rows of {@code parent}. */
    List<Link> ownedBy(Schema.Table parent) {
        return ownedBy.getOrDefault(parent.name(), List.of());
    }

    /** Foreign keys through which rows of other tables extend rows of {@code parent}. */
    List<Link> extensionsOf(Schema.Table parent) {
        return extensionsOf.getOrDefault(parent.name(), List.of());
    }
}
