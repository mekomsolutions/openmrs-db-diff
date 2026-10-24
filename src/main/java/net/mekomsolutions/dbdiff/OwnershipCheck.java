package net.mekomsolutions.dbdiff;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.mekomsolutions.dbdiff.IdentityResolver.Side;

/**
 * Finds rows of a patient's record that the target already has, but attached elsewhere: a visit that belongs to
 * another patient, an encounter in another visit, an obs in another group, and so on.
 *
 * <p>A row counts as present in the target when its identity (its uuid) is there. Legacy imports generated uuids
 * from local ids, so two sites can hold unrelated rows under the same uuid. Copying the patient's missing rows onto
 * such a row would graft them onto someone else's record, so a patient with conflicts is not synced at all.
 *
 * <p>Only foreign keys into the patient's own data are compared (person, patient, visit, encounter, obs, ...).
 * Metadata and the users who recorded the data may legitimately differ between the databases.
 *
 * <p>One kind of mismatch is expected: where a database merge did not know a row's parent yet (its patient, visit,
 * encounter, location, creator, ...), it pointed the row at a placeholder parent whose uuid is a fixed marker, and
 * dated it 1970. Such a row is not a conflict but a <em>placeholder</em>, to be written over with the source row.
 * Any foreign key may point at a placeholder parent, metadata and users included.
 */
final class OwnershipCheck {

    /**
     * A present row whose {@code column} points at a different parent in the target than in the source.
     *
     * @param sourceParent the parent's identity in the source, or null when the column is empty there
     * @param targetParent the parent's identity in the target, or null when the column is empty there
     */
    record Conflict(Schema.Table table, SourceRow row, Schema.ForeignKey fk, String sourceParent,
                    String targetParent) {

        String describe() {
            return table.name() + " " + row.describe() + ": " + fk.column() + " is " + readable(sourceParent)
                    + " in the source but " + readable(targetParent) + " in the target";
        }

        private String readable(String parent) {
            return parent == null ? "empty" : fk.refTable() + " " + IdentityResolver.readable(parent);
        }
    }

    /**
     * What the check found.
     *
     * @param placeholders rows that point at a placeholder parent in the target, by table, to write over
     * @param conflicts every other row attached elsewhere in the target
     */
    record Findings(Map<Schema.Table, List<SourceRow>> placeholders, List<Conflict> conflicts) {}

    private final Schema source;
    private final IdentityResolver resolver;
    private final String placeholder;

    /** @param placeholderUuid see {@link SyncConfig#placeholderUuid()}; null or empty for none */
    OwnershipCheck(Schema source, IdentityResolver resolver, String placeholderUuid) {
        this.source = source;
        this.resolver = resolver;
        this.placeholder = placeholderUuid == null || placeholderUuid.isBlank() ? null : Db.normUuid(placeholderUuid);
    }

    /** Checks the rows of {@code record} that are not in {@code missing}, i.e. that the target has. */
    Findings check(PatientRecord record, Map<Schema.Table, List<SourceRow>> missing) throws SQLException {
        List<Conflict> all = mismatches(record, missing);
        // a row on a placeholder parent is rewritten whole, which also settles its other mismatches
        Map<Schema.Table, Map<String, SourceRow>> placeholders = new LinkedHashMap<>();
        for (Conflict c : all) {
            if (onPlaceholder(c)) {
                placeholders.computeIfAbsent(c.table(), t -> new LinkedHashMap<>()).put(c.row().identity(), c.row());
            }
        }
        List<Conflict> conflicts = all.stream()
                .filter(c -> !placeholders.getOrDefault(c.table(), Map.of()).containsKey(c.row().identity()))
                .toList();
        Map<Schema.Table, List<SourceRow>> rows = new LinkedHashMap<>();
        placeholders.forEach((table, byIdentity) -> rows.put(table, new ArrayList<>(byIdentity.values())));
        return new Findings(rows, conflicts);
    }

    /** A row of a uuid table (so it can be updated by uuid) whose column points at a placeholder parent. */
    private boolean onPlaceholder(Conflict c) {
        return placeholder != null && c.table().hasUuid() && placeholder.equals(c.targetParent());
    }

    /**
     * Present rows whose foreign keys point elsewhere in the target than in the source: every mismatch on the
     * patient's own data, and on other tables (metadata, users) only those pointing at a placeholder.
     */
    private List<Conflict> mismatches(PatientRecord record, Map<Schema.Table, List<SourceRow>> missing)
            throws SQLException {
        Set<String> patientTables = new HashSet<>();
        record.tables().forEach(t -> patientTables.add(t.name()));

        List<Conflict> mismatches = new ArrayList<>();
        for (Map.Entry<Schema.Table, List<SourceRow>> e : record.rows().entrySet()) {
            Schema.Table table = e.getKey();
            List<SourceRow> present = present(e.getValue(), missing.getOrDefault(table, List.of()));
            if (present.isEmpty()) {
                continue;
            }
            for (Schema.ForeignKey fk : table.foreignKeys()) {
                boolean patientData = patientTables.contains(fk.refTable());
                if (patientData || placeholder != null) {
                    for (Conflict c : compare(table, fk, present)) {
                        if (patientData || onPlaceholder(c)) {
                            mismatches.add(c);
                        }
                    }
                }
            }
        }
        return mismatches;
    }

    private List<Conflict> compare(Schema.Table table, Schema.ForeignKey fk, List<SourceRow> present)
            throws SQLException {
        List<Conflict> conflicts = new ArrayList<>();
        Schema.Table parent = source.table(fk.refTable());
        List<String> identities = present.stream().map(SourceRow::identity).toList();
        resolver.prefetchValues(Side.TARGET, table, fk.column(), identities);

        List<Object> targetValues = new ArrayList<>();
        for (SourceRow row : present) {
            targetValues.add(resolver.valueFor(Side.TARGET, table, fk.column(), row.identity()));
        }
        resolver.prefetchIdentities(Side.SOURCE, parent, fk.refColumn(), SourceReader.values(present, fk.column()));
        resolver.prefetchIdentities(Side.TARGET, parent, fk.refColumn(),
                targetValues.stream().filter(Objects::nonNull).toList());

        for (int i = 0; i < present.size(); i++) {
            SourceRow row = present.get(i);
            String sourceParent = resolver.identityOf(Side.SOURCE, parent, fk.refColumn(), row.values().get(fk.column()));
            String targetParent = resolver.identityOf(Side.TARGET, parent, fk.refColumn(), targetValues.get(i));
            if (!Objects.equals(sourceParent, targetParent)) {
                conflicts.add(new Conflict(table, row, fk, sourceParent, targetParent));
            }
        }
        return conflicts;
    }

    private static List<SourceRow> present(List<SourceRow> rows, List<SourceRow> missing) {
        if (missing.isEmpty()) {
            return rows;
        }
        Set<String> absent = new HashSet<>();
        missing.forEach(r -> absent.add(r.identity()));
        return rows.stream().filter(r -> !absent.contains(r.identity())).toList();
    }
}
