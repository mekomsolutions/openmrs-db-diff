package net.mekomsolutions.dbdiff;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the table-by-table timezone run, its undo and the site key helper share: which tables they work on, the key
 * ranges never to touch, and which rows are placeholders.
 */
final class TimezoneTables {

    /** Never converted with --all-tables, besides the excluded tables of the config: bookkeeping, not data. */
    private static final Set<String> SYSTEM_TABLES = Set.of("global_property", "event_records", "markers",
            "failed_events");
    private static final List<String> SYSTEM_PREFIXES = List.of("liquibase", "scheduler", "dbdiff_");
    private static final Set<String> INTEGER_TYPES = Set.of("int", "integer", "bigint", "smallint", "mediumint",
            "tinyint");
    static final LocalDateTime EPOCH_DAY = LocalDateTime.of(1970, 1, 1, 0, 0);

    private TimezoneTables() {}

    /** A table to work on, or why it cannot be. */
    record Candidate(Schema.Table table, String problem) {

        boolean usable() {
            return problem == null;
        }

        String pk() {
            return table.primaryKey().get(0);
        }
    }

    /**
     * The tables named, in the order given, or with {@code all} every table that has a DATETIME column, minus the
     * excluded tables of the config and the bookkeeping tables. A named table that does not exist is an error; one that
     * cannot be converted, or is one of the excluded tables, comes back with its problem.
     */
    static List<Candidate> select(Schema schema, SyncConfig config, List<String> names, boolean all) {
        List<Candidate> selected = new ArrayList<>();
        if (all) {
            for (Schema.Table table : schema.tables()) {
                if (datetimeColumns(table).isEmpty() || isSystem(table.name()) || config.isExcluded(table.name())) {
                    continue;
                }
                selected.add(new Candidate(table, problem(table)));
            }
            return selected;
        }
        for (String name : names) {
            Schema.Table table = schema.table(name);
            if (table == null) {
                table = schema.tables().stream().filter(t -> t.name().equalsIgnoreCase(name)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("No table " + name + " in the database"));
            }
            if (table.name().toLowerCase(Locale.ROOT).startsWith("dbdiff_")) {
                throw new IllegalArgumentException(table.name() + " is this tool's own table");
            }
            selected.add(new Candidate(table, config.isExcluded(table.name()) ? EXCLUDED : problem(table)));
        }
        return selected;
    }

    /** The problem of a table listed in the config's excluded tables: it was not copied or merged, so never touched. */
    static final String EXCLUDED = "in the excluded tables of the config (not merged)";

    static boolean isSystem(String table) {
        String name = table.toLowerCase(Locale.ROOT);
        return SYSTEM_TABLES.contains(name) || SYSTEM_PREFIXES.stream().anyMatch(name::startsWith);
    }

    /** Why rows of {@code table} cannot be converted one by one, or null when they can. */
    private static String problem(Schema.Table table) {
        if (datetimeColumns(table).isEmpty()) {
            return "no DATETIME column";
        }
        Schema.Column created = table.column("date_created");
        if (created == null || !"datetime".equals(created.dataType())) {
            return "no DATETIME date_created column to tell which rows to convert";
        }
        if (table.primaryKey().size() != 1) {
            return table.primaryKey().isEmpty() ? "no primary key" : "composite primary key";
        }
        if (!INTEGER_TYPES.contains(table.column(table.primaryKey().get(0)).dataType())) {
            return "primary key is not an integer";
        }
        return null;
    }

    static List<Schema.Column> datetimeColumns(Schema.Table table) {
        return table.columns().stream().filter(c -> "datetime".equals(c.dataType())).toList();
    }

    static boolean hasIntegerKey(Schema.Table table) {
        return table.primaryKey().size() == 1
                && INTEGER_TYPES.contains(table.column(table.primaryKey().get(0)).dataType());
    }

    /**
     * Primary-key ranges never to touch, by table, read from CSV {@code table,pk_from,pk_to,label} (a blank bound is
     * open). Extra columns, blank lines, a header and lines starting with # are ignored.
     */
    static final class ExcludedKeys {

        record Range(long from, long to, String label) {
            boolean contains(long key) {
                return key >= from && key <= to;
            }
        }

        private final Map<String, List<Range>> byTable;

        private ExcludedKeys(Map<String, List<Range>> byTable) {
            this.byTable = byTable;
        }

        static ExcludedKeys none() {
            return new ExcludedKeys(Map.of());
        }

        static ExcludedKeys load(Path file) throws IOException {
            if (file == null) {
                return none();
            }
            Map<String, List<Range>> byTable = new HashMap<>();
            for (List<String> row : CsvReader.read(file)) {
                String table = row.get(0).trim();
                if (table.isEmpty() || table.startsWith("#") || table.equalsIgnoreCase("table")) {
                    continue;
                }
                if (row.size() < 3) {
                    throw new IllegalArgumentException("Expected table,pk_from,pk_to,label in " + file + ": " + row);
                }
                long from = bound(row.get(1), Long.MIN_VALUE, file);
                long to = bound(row.get(2), Long.MAX_VALUE, file);
                if (from > to) {
                    throw new IllegalArgumentException("pk_from " + from + " is after pk_to " + to + " in " + file);
                }
                byTable.computeIfAbsent(table.toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(new Range(from, to, row.size() > 3 ? row.get(3).trim() : ""));
            }
            return new ExcludedKeys(byTable);
        }

        private static long bound(String text, long open, Path file) {
            if (text.isBlank()) {
                return open;
            }
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Not a key in " + file + ": " + text);
            }
        }

        List<Range> of(String table) {
            return byTable.getOrDefault(table.toLowerCase(Locale.ROOT), List.of());
        }

        /** The label of the range of {@code table} holding {@code key}, or null when none does. */
        Range find(String table, long key) {
            for (Range r : of(table)) {
                if (r.contains(key)) {
                    return r;
                }
            }
            return null;
        }
    }

    /**
     * Tells placeholder parents: rows a database merge pointed rows at while their real parent was unknown. A parent
     * is a placeholder when its uuid is the placeholder uuid, or when it is dated 1970-01-01 and itself points at a
     * row with that uuid (a placeholder visit of the placeholder patient, say). Only rows with the placeholder uuid
     * are loaded from every referenced table, and 1970 rows only from tables that point at them; both are cached.
     */
    static final class Placeholders {

        private final Connection conn;
        private final Schema schema;
        private final String uuid;
        private final Map<String, Set<Long>> markedByTable = new HashMap<>();
        private final Map<String, Set<Long>> parentsByTable = new HashMap<>();

        Placeholders(Connection conn, Schema schema, String placeholderUuid) {
            this.conn = conn;
            this.schema = schema;
            this.uuid = placeholderUuid == null || placeholderUuid.isBlank() ? null : placeholderUuid.trim();
        }

        String uuid() {
            return uuid;
        }

        /** Foreign keys of {@code table} into tables that can hold placeholder parents, by column. */
        Map<String, Schema.ForeignKey> foreignKeys(Schema.Table table) {
            Map<String, Schema.ForeignKey> fks = new LinkedHashMap<>();
            if (uuid == null) {
                return fks;
            }
            for (Schema.ForeignKey fk : table.foreignKeys()) {
                Schema.Table ref = schema.table(fk.refTable());
                if (ref != null && (ref.hasUuid() || uuidParent(ref) != null) && hasIntegerKey(ref)
                        && ref.primaryKey().get(0).equalsIgnoreCase(fk.refColumn())) {
                    fks.put(fk.column(), fk);
                }
            }
            return fks;
        }

        /** Keys of the placeholder parents in {@code table}. */
        Set<Long> parents(String table) throws SQLException {
            Set<Long> cached = parentsByTable.get(table);
            if (cached != null) {
                return cached;
            }
            Schema.Table t = schema.table(table);
            Set<Long> keys = new HashSet<>(marked(table));
            Map<String, Schema.ForeignKey> fks = foreignKeys(t);
            if (t.hasColumn("date_created") && !fks.isEmpty()) {
                Map<String, Set<Long>> markedRefs = new LinkedHashMap<>();
                for (Map.Entry<String, Schema.ForeignKey> e : fks.entrySet()) {
                    Set<Long> marked = marked(e.getValue().refTable());
                    if (!marked.isEmpty()) {
                        markedRefs.put(e.getKey(), marked);
                    }
                }
                if (!markedRefs.isEmpty()) {
                    String pk = t.primaryKey().get(0);
                    String sql = "SELECT " + Db.q(pk) + ", " + String.join(", ",
                            markedRefs.keySet().stream().map(Db::q).toList()) + " FROM " + Db.q(table)
                            + " WHERE " + Db.q("date_created") + " >= ? AND " + Db.q("date_created") + " < ?";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setObject(1, EPOCH_DAY);
                        ps.setObject(2, EPOCH_DAY.plusDays(1));
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                int i = 2;
                                for (Set<Long> marked : markedRefs.values()) {
                                    long ref = rs.getLong(i++);
                                    if (!rs.wasNull() && marked.contains(ref)) {
                                        keys.add(rs.getLong(1));
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            parentsByTable.put(table, keys);
            return keys;
        }

        /**
         * For a table without uuid whose integer primary key is also a foreign key to a table with one (patient to
         * person), that foreign key; null otherwise.
         */
        private Schema.ForeignKey uuidParent(Schema.Table table) {
            if (table.hasUuid() || !hasIntegerKey(table)) {
                return null;
            }
            Schema.ForeignKey fk = table.foreignKey(table.primaryKey().get(0));
            Schema.Table ref = fk == null ? null : schema.table(fk.refTable());
            return ref != null && ref != table && ref.hasUuid() && hasIntegerKey(ref)
                    && ref.primaryKey().get(0).equalsIgnoreCase(fk.refColumn()) ? fk : null;
        }

        /** Keys of the rows of {@code table} whose uuid is the placeholder uuid. */
        private Set<Long> marked(String table) throws SQLException {
            Set<Long> cached = markedByTable.get(table);
            if (cached != null) {
                return cached;
            }
            Schema.Table t = schema.table(table);
            Set<Long> keys = new HashSet<>();
            Schema.ForeignKey shared = t == null || t.hasUuid() ? null : uuidParent(t);
            if (shared != null) {
                keys = marked(shared.refTable()); // patient rows share their key with person rows
            } else if (uuid != null && t != null && t.hasUuid() && hasIntegerKey(t)) {
                try (PreparedStatement ps = conn.prepareStatement("SELECT " + Db.q(t.primaryKey().get(0)) + " FROM "
                        + Db.q(table) + " WHERE " + Db.q("uuid") + " = ?")) {
                    ps.setString(1, uuid);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            keys.add(rs.getLong(1));
                        }
                    }
                }
            }
            markedByTable.put(table, keys);
            return keys;
        }
    }
}
