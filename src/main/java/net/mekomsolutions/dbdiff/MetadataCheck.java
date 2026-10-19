package net.mekomsolutions.dbdiff;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import net.mekomsolutions.dbdiff.MetadataDecisions.Action;

/**
 * Compares the metadata of the two databases before a sync. Metadata tables are the excluded tables listed in the
 * config's metadata tables file (concepts, locations, attribute types, ...), as opposed to excluded tables such as
 * logs, event queues or Liquibase's; they must have a uuid and exist in both databases. The sync never compares
 * these tables, but a patient's rows can only be copied when the metadata they point at is in the target.
 *
 * <p>For every metadata row the source has and the target lacks (by uuid), the check counts how many synced rows
 * use it, looks in the target for a row with the same name, and follows its own references to other missing
 * metadata (a location's parent, for example). It writes:
 *
 * <ul>
 *   <li>{@code metadata.csv}: every such row, used or not;
 *   <li>{@code metadata-decisions.csv}: a suggested decision for each row that patient data needs: MAP to the one
 *       target row with the same name, COPY when there is none, SKIP when there are several to choose from. Once
 *       reviewed, it goes in the config folder, where the sync reads it (see {@link MetadataDecisions}).
 * </ul>
 *
 * Reads both databases and writes nothing to either.
 */
final class MetadataCheck {

    /** A metadata row the source has and the target lacks. */
    record Finding(Schema.Table table, String uuid, String name, long usedBy, Set<String> usedIn,
                   List<String> neededBy, List<String> sameNameInTarget) {

        boolean needed() {
            return usedBy > 0 || !neededBy.isEmpty();
        }

        Action suggestion() {
            if (sameNameInTarget.size() == 1) {
                return Action.MAP;
            }
            return sameNameInTarget.isEmpty() ? Action.COPY : Action.SKIP;
        }
    }

    private static final String CONCEPT_NAME_SQL = "SELECT n.name FROM concept_name n JOIN concept c"
            + " ON c.concept_id = n.concept_id WHERE c.uuid = ? AND n.concept_name_type = 'FULLY_SPECIFIED'"
            + " ORDER BY n.locale_preferred DESC, n.concept_name_id LIMIT 1";
    private static final String CONCEPT_BY_NAME_SQL = "SELECT DISTINCT c.uuid FROM concept c JOIN concept_name n"
            + " ON n.concept_id = c.concept_id WHERE n.name = ? AND n.concept_name_type = 'FULLY_SPECIFIED'";

    private final Connection source;
    private final Connection target;
    private final Schema sourceSchema;
    private final Schema targetSchema;
    private final SyncConfig config;
    private final SyncScope scope;

    private final Map<String, Set<String>> targetUuids = new HashMap<>();
    private final Map<String, Map<String, Map<String, Object>>> sourceRows = new HashMap<>();

    MetadataCheck(Connection source, Connection target, Schema sourceSchema, Schema targetSchema, SyncConfig config) {
        this.source = source;
        this.target = target;
        this.sourceSchema = sourceSchema;
        this.targetSchema = targetSchema;
        this.config = config;
        this.scope = new SyncScope(targetSchema, config);
    }

    /** Metadata rows missing from the target, needed ones first, then by table and how much they are used. */
    List<Finding> run() throws SQLException {
        Map<String, Builder> found = new LinkedHashMap<>();
        if (config.metadataTables().isEmpty()) {
            throw new IllegalArgumentException("No metadata tables are listed. List them, one per line, in the file"
                    + " metadataTablesFile names in the config (default metadata-tables.txt, next to it).");
        }
        for (Schema.Table table : metadataTables()) {
            List<String> missing = missingFromTarget(table);
            readSourceRows(table, missing);
            for (String uuid : missing) {
                found.put(key(table, uuid), new Builder(table, uuid));
            }
        }
        countUses(found);
        followReferences(found);
        List<Finding> findings = new ArrayList<>();
        for (Builder b : found.values()) {
            findings.add(b.build(b.needed() ? sameNameInTarget(b.table, b.name) : List.of()));
        }
        findings.sort(Comparator.comparing((Finding f) -> !f.needed())
                .thenComparing(f -> f.table().name())
                .thenComparing(Finding::usedBy, Comparator.reverseOrder())
                .thenComparing(Finding::uuid));
        return findings;
    }

    /** Writes metadata.csv and metadata-decisions.csv into {@code dir}. */
    static void write(Path dir, List<Finding> findings) throws IOException {
        Files.createDirectories(dir);
        try (CsvWriter all = new CsvWriter(dir.resolve("metadata.csv"), List.of("table", "uuid", "name",
                "used_by_rows", "used_in", "needed_by", "same_name_in_target", "suggestion"));
             CsvWriter decisions = new CsvWriter(dir.resolve("metadata-decisions.csv"), MetadataDecisions.HEADER)) {
            for (Finding f : findings) {
                all.row(List.of(f.table().name(), f.uuid(), nullToEmpty(f.name()), Long.toString(f.usedBy()),
                        String.join(" ", f.usedIn()), String.join(" ", f.neededBy()), String.join(" ", f.sameNameInTarget()),
                        f.needed() ? f.suggestion().name() : ""));
                if (f.needed()) {
                    String targetUuid = f.suggestion() == Action.MAP ? f.sameNameInTarget().get(0) : "";
                    decisions.row(List.of(f.table().name(), f.uuid(), nullToEmpty(f.name()), f.suggestion().name(),
                            targetUuid));
                }
            }
        }
    }

    /** The listed metadata tables that can be compared; says why each of the others is left out. */
    private List<Schema.Table> metadataTables() {
        List<Schema.Table> tables = new ArrayList<>();
        for (String name : config.metadataTables()) {
            Schema.Table table = sourceSchema.table(name);
            if (table == null) {
                Log.warn("Metadata table " + name + " is not in the source database, skipping it");
            } else if (!config.isExcluded(name)) {
                Log.warn("Metadata table " + name + " is not in the excluded tables, so it is synced with each"
                        + " patient's data instead; the metadata check skips it");
            } else if (!isMetadata(table)) {
                Log.warn("Metadata table " + name + " has no uuid column in both databases, skipping it");
            } else {
                tables.add(table);
            }
        }
        return tables;
    }

    /** A listed metadata table with a uuid, present in both databases. */
    private boolean isMetadata(Schema.Table table) {
        Schema.Table inTarget = targetSchema.table(table.name());
        return config.isMetadata(table.name()) && table.hasUuid() && inTarget != null && inTarget.hasUuid();
    }

    private List<String> missingFromTarget(Schema.Table table) throws SQLException {
        Set<String> inTarget = targetUuids(table);
        List<String> missing = new ArrayList<>();
        for (String uuid : uuids(source, table)) {
            if (!inTarget.contains(uuid)) {
                missing.add(uuid);
            }
        }
        return missing;
    }

    /** Counts, for each missing row, the rows of synced tables that point at it. */
    private void countUses(Map<String, Builder> found) throws SQLException {
        Map<Schema.Table, List<Builder>> byTable = new LinkedHashMap<>();
        found.values().forEach(b -> byTable.computeIfAbsent(b.table, t -> new ArrayList<>()).add(b));
        for (Schema.Table child : sourceSchema.tables()) {
            if (!scope.includes(child)) {
                continue;
            }
            for (Schema.ForeignKey fk : child.foreignKeys()) {
                Schema.Table parent = sourceSchema.table(fk.refTable());
                List<Builder> missing = parent == null ? null : byTable.get(parent);
                if (missing == null) {
                    continue;
                }
                Map<String, Builder> byKey = new HashMap<>();
                List<Object> keys = new ArrayList<>();
                for (Builder b : missing) {
                    Object key = b.row().get(fk.refColumn());
                    if (key != null) {
                        byKey.put(Db.key(key), b);
                        keys.add(key);
                    }
                }
                countReferences(child, fk, keys, byKey);
            }
        }
    }

    private void countReferences(Schema.Table child, Schema.ForeignKey fk, List<Object> keys,
                                 Map<String, Builder> byKey) throws SQLException {
        Schema.Column refColumn = sourceSchema.table(fk.refTable()).column(fk.refColumn());
        String sql = "SELECT " + Db.q(fk.column()) + ", COUNT(*) FROM " + Db.q(child.name()) + " WHERE "
                + Db.q(fk.column()) + " IN (";
        for (List<Object> batch : Db.batches(keys)) {
            try (PreparedStatement ps = source.prepareStatement(sql + Db.placeholders(batch.size()) + ") GROUP BY "
                    + Db.q(fk.column()))) {
                for (int i = 0; i < batch.size(); i++) {
                    ps.setObject(i + 1, batch.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Builder b = byKey.get(Db.key(Db.read(rs, 1, refColumn)));
                        if (b != null) {
                            b.usedBy += rs.getLong(2);
                            b.usedIn.add(child.name());
                        }
                    }
                }
            }
        }
    }

    /** Marks the missing rows that needed rows point at (a location's parent, ...) as needed too. */
    private void followReferences(Map<String, Builder> found) throws SQLException {
        Deque<Builder> todo = new ArrayDeque<>();
        found.values().stream().filter(Builder::needed).forEach(todo::add);
        while (!todo.isEmpty()) {
            Builder b = todo.poll();
            for (Schema.ForeignKey fk : b.table.foreignKeys()) {
                Object value = b.row().get(fk.column());
                Schema.Table parent = sourceSchema.table(fk.refTable());
                if (value == null || parent == null || !isMetadata(parent)) {
                    continue;
                }
                String parentUuid = uuidWhere(parent, fk.refColumn(), value);
                Builder p = parentUuid == null ? null : found.get(key(parent, parentUuid));
                if (p != null) {
                    boolean wasNeeded = p.needed();
                    p.neededBy.add(b.table.name() + " " + b.uuid);
                    if (!wasNeeded) {
                        todo.add(p);
                    }
                }
            }
        }
    }

    /** Target uuids of rows with the same name, when the table has names. */
    private List<String> sameNameInTarget(Schema.Table table, String name) throws SQLException {
        if (name == null) {
            return List.of();
        }
        String sql;
        if (table.name().equals("concept")) {
            sql = CONCEPT_BY_NAME_SQL;
        } else if (table.column("name") != null) {
            sql = "SELECT uuid FROM " + Db.q(table.name()) + " WHERE name = ? ORDER BY uuid";
        } else {
            return List.of();
        }
        List<String> uuids = new ArrayList<>();
        try (PreparedStatement ps = target.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    uuids.add(Db.normUuid(rs.getString(1)));
                }
            }
        }
        return uuids;
    }

    private String nameOf(Schema.Table table, String uuid, Map<String, Object> row) throws SQLException {
        if (table.column("name") != null) {
            Object name = row.get("name");
            return name == null ? null : name.toString();
        }
        if (table.name().equals("concept") && sourceSchema.table("concept_name") != null) {
            try (PreparedStatement ps = source.prepareStatement(CONCEPT_NAME_SQL)) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        }
        return null;
    }

    private Set<String> targetUuids(Schema.Table table) throws SQLException {
        Set<String> uuids = targetUuids.get(table.name());
        if (uuids == null) {
            uuids = new HashSet<>(uuids(target, table));
            targetUuids.put(table.name(), uuids);
        }
        return uuids;
    }

    private static List<String> uuids(Connection connection, Schema.Table table) throws SQLException {
        List<String> uuids = new ArrayList<>();
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT uuid FROM " + Db.q(table.name()) + " WHERE uuid IS NOT NULL")) {
            while (rs.next()) {
                uuids.add(Db.normUuid(rs.getString(1)));
            }
        }
        return uuids;
    }

    /** Reads the source rows with these uuids in a few queries. */
    private void readSourceRows(Schema.Table table, List<String> uuids) throws SQLException {
        Map<String, Map<String, Object>> rows = sourceRows.computeIfAbsent(table.name(), t -> new HashMap<>());
        String sql = "SELECT * FROM " + Db.q(table.name()) + " WHERE uuid IN (";
        for (List<String> batch : Db.batches(uuids)) {
            try (PreparedStatement ps = source.prepareStatement(sql + Db.placeholders(batch.size()) + ")")) {
                for (int i = 0; i < batch.size(); i++) {
                    ps.setString(i + 1, batch.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = Db.readRow(rs, table);
                        rows.put(Db.normUuid(row.get("uuid")), row);
                    }
                }
            }
        }
    }

    /** The source row with this uuid, read once. */
    private Map<String, Object> sourceRow(Schema.Table table, String uuid) throws SQLException {
        Map<String, Map<String, Object>> rows = sourceRows.computeIfAbsent(table.name(), t -> new HashMap<>());
        if (!rows.containsKey(uuid)) {
            try (PreparedStatement ps = source.prepareStatement(
                    "SELECT * FROM " + Db.q(table.name()) + " WHERE uuid = ?")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    rows.put(uuid, rs.next() ? Db.readRow(rs, table) : Map.of());
                }
            }
        }
        return rows.get(uuid);
    }

    private String uuidWhere(Schema.Table table, String column, Object value) throws SQLException {
        try (PreparedStatement ps = source.prepareStatement(
                "SELECT uuid FROM " + Db.q(table.name()) + " WHERE " + Db.q(column) + " = ?")) {
            ps.setObject(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Db.normUuid(rs.getString(1)) : null;
            }
        }
    }

    private static String key(Schema.Table table, String uuid) {
        return table.name() + " " + uuid;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** A finding being put together. */
    private final class Builder {
        final Schema.Table table;
        final String uuid;
        final Set<String> usedIn = new TreeSet<>();
        final List<String> neededBy = new ArrayList<>();
        long usedBy;
        String name;
        private Map<String, Object> row;

        Builder(Schema.Table table, String uuid) {
            this.table = table;
            this.uuid = uuid;
        }

        boolean needed() {
            return usedBy > 0 || !neededBy.isEmpty();
        }

        Map<String, Object> row() throws SQLException {
            if (row == null) {
                row = sourceRow(table, uuid);
                name = nameOf(table, uuid, row);
            }
            return row;
        }

        Finding build(List<String> sameName) throws SQLException {
            row();
            return new Finding(table, uuid, name, usedBy, new TreeSet<>(usedIn), List.copyOf(neededBy), sameName);
        }
    }

    /** Per table: how many rows are missing and how many of those patient data needs. */
    static Map<String, int[]> totals(List<Finding> findings) {
        Map<String, int[]> totals = new TreeMap<>();
        for (Finding f : findings) {
            int[] t = totals.computeIfAbsent(f.table().name(), k -> new int[2]);
            t[0]++;
            if (f.needed()) {
                t[1]++;
            }
        }
        return totals;
    }
}
