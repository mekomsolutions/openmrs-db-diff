package net.mekomsolutions.datasync;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates database-specific key values into identities that mean the same row in both databases, and back.
 *
 * <p>A row's identity is its uuid. Tables without a uuid column (patient, drug_order, user_role, ...) get an
 * identity built from their {@link Schema.Table#identityColumns() identity columns}, with every foreign key
 * replaced by the identity of the row it points to. So patient 12 in one database and patient 4071 in the other
 * share an identity when both point at the person with the same uuid.
 *
 * <p>Lookups are cached for the whole run. Target lookups go through a read-only connection and therefore only see
 * committed data; rows the sync inserts are tracked here as <em>pending</em> until their transaction commits
 * ({@link #commitInserts()}) or rolls back ({@link #discardInserts()}).
 */
final class IdentityResolver {

    enum Side { SOURCE, TARGET }

    private static final String NO_UUID = "\u0000none";
    private static final Object NO_VALUE = new Object();
    private static final String SEP = "\u001F";
    private static final int MAX_DEPTH = 8;

    private final Schema schema;
    private final Map<Side, Connection> conns;

    /** side|table|column -> value key -> uuid (or NO_UUID). Tables with a uuid column only. */
    private final Map<String, Map<String, String>> valueToUuid = new HashMap<>();
    /** side|table|column -> uuid -> value (or NO_VALUE). Tables with a uuid column only. */
    private final Map<String, Map<String, Object>> uuidToValue = new HashMap<>();
    /** side|table -> fully loaded table without a uuid column. */
    private final Map<String, LoadedTable> loaded = new HashMap<>();
    /** table name -> identity -> row inserted into the target by the transaction in progress. */
    private final Map<String, Map<String, Map<String, Object>>> pendingInserts = new LinkedHashMap<>();

    /**
     * @param schema the source schema; both databases are expected to share it
     * @param targetReadOnly a target connection that never takes part in the sync transaction
     */
    IdentityResolver(Schema schema, Connection source, Connection targetReadOnly) {
        this.schema = schema;
        this.conns = Map.of(Side.SOURCE, source, Side.TARGET, targetReadOnly);
    }

    /** Human-readable form of an identity, for logs and reports. */
    static String readable(String identity) {
        return identity.replace(SEP, "/");
    }

    // ---------------------------------------------------------------------------------------------- lookups

    /** Identity of the row in {@code table} whose {@code column} equals {@code value}, or null if there is none. */
    String identityOf(Side side, Schema.Table table, String column, Object value) throws SQLException {
        return identityOf(side, table, column, value, 0);
    }

    private String identityOf(Side side, Schema.Table table, String column, Object value, int depth)
            throws SQLException {
        if (value == null) {
            return null;
        }
        if (table.hasUuid()) {
            if (column.equals("uuid")) {
                return Db.normUuid(value);
            }
            Map<String, String> cache = valueToUuid.computeIfAbsent(cacheKey(side, table, column), k -> new HashMap<>());
            String key = Db.key(value);
            if (!cache.containsKey(key)) {
                prefetchIdentities(side, table, column, List.of(value));
            }
            String uuid = cache.get(key);
            return NO_UUID.equals(uuid) ? null : uuid;
        }
        LoadedTable lt = load(side, table);
        Integer idx = lt.index(column).get(Db.key(value));
        return idx == null ? null : lt.identity(idx, depth);
    }

    /** Value of {@code column} in the row of {@code table} with the given identity, or null if there is none. */
    Object valueFor(Side side, Schema.Table table, String column, String identity) throws SQLException {
        if (identity == null) {
            return null;
        }
        Map<String, Object> pending = side == Side.TARGET ? pendingRow(table, identity) : null;
        if (pending != null) {
            return pending.get(column);
        }
        if (table.hasUuid()) {
            Map<String, Object> cache = uuidToValue.computeIfAbsent(cacheKey(side, table, column), k -> new HashMap<>());
            if (!cache.containsKey(identity)) {
                prefetchValues(side, table, column, List.of(identity));
            }
            Object v = cache.get(identity);
            return v == NO_VALUE ? null : v;
        }
        LoadedTable lt = load(side, table);
        Integer idx = lt.byIdentity().get(identity);
        return idx == null ? null : lt.rows.get(idx).get(column);
    }

    /** The subset of {@code identities} that have a row in {@code table} on {@code side}. */
    Set<String> existing(Side side, Schema.Table table, Collection<String> identities) throws SQLException {
        if (table.hasUuid()) {
            prefetchValues(side, table, "uuid", identities);
        }
        Set<String> found = new HashSet<>();
        for (String identity : identities) {
            if (exists(side, table, identity)) {
                found.add(identity);
            }
        }
        return found;
    }

    private boolean exists(Side side, Schema.Table table, String identity) throws SQLException {
        if (side == Side.TARGET && pendingRow(table, identity) != null) {
            return true;
        }
        if (table.hasUuid()) {
            return valueFor(side, table, "uuid", identity) != null;
        }
        return load(side, table).byIdentity().containsKey(identity);
    }

    /** Identity of a full row read from {@code side}. */
    String rowIdentity(Side side, Schema.Table table, Map<String, Object> row) throws SQLException {
        return rowIdentity(side, table, row, 0);
    }

    /** Identities of many rows of one table, looking up their foreign keys in a few queries. */
    List<String> rowIdentities(Side side, Schema.Table table, List<Map<String, Object>> rows) throws SQLException {
        if (!table.hasUuid()) {
            for (String col : table.identityColumns()) {
                Schema.ForeignKey fk = table.foreignKey(col);
                if (fk != null) {
                    prefetchIdentities(side, schema.table(fk.refTable()), fk.refColumn(), columnValues(rows, col));
                }
            }
        }
        List<String> identities = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            identities.add(rowIdentity(side, table, row));
        }
        return identities;
    }

    private String rowIdentity(Side side, Schema.Table table, Map<String, Object> row, int depth) throws SQLException {
        if (table.hasUuid()) {
            return Db.normUuid(row.get("uuid"));
        }
        List<String> parts = new ArrayList<>();
        for (String col : table.identityColumns()) {
            Object v = row.get(col);
            Schema.ForeignKey fk = table.foreignKey(col);
            if (v == null) {
                parts.add("NULL");
            } else if (fk != null && depth < MAX_DEPTH) {
                String ref = identityOf(side, schema.table(fk.refTable()), fk.refColumn(), v, depth + 1);
                parts.add(ref == null ? "?" + Db.key(v) : ref);
            } else {
                parts.add(Db.key(v));
            }
        }
        return String.join(SEP, parts);
    }

    /** Looks up the uuids behind many values of one column in a few queries. */
    void prefetchIdentities(Side side, Schema.Table table, String column, Collection<?> values) throws SQLException {
        if (!table.hasUuid()) {
            load(side, table);
            return;
        }
        if (column.equals("uuid")) {
            return;
        }
        Map<String, String> cache = valueToUuid.computeIfAbsent(cacheKey(side, table, column), k -> new HashMap<>());
        Map<String, Object> reverse = uuidToValue.computeIfAbsent(cacheKey(side, table, column), k -> new HashMap<>());
        Map<String, Object> todo = new LinkedHashMap<>();
        for (Object v : values) {
            String k = Db.key(v);
            if (k != null && !cache.containsKey(k)) {
                todo.put(k, v);
            }
        }
        Schema.Column col = table.column(column);
        String sql = "SELECT " + Db.q(column) + ", uuid FROM " + Db.q(table.name()) + " WHERE " + Db.q(column) + " IN (";
        for (List<Object> batch : Db.batches(new ArrayList<>(todo.values()))) {
            try (PreparedStatement ps = conns.get(side).prepareStatement(sql + Db.placeholders(batch.size()) + ")")) {
                for (int j = 0; j < batch.size(); j++) {
                    ps.setObject(j + 1, batch.get(j));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Object v = Db.read(rs, 1, col);
                        String uuid = Db.normUuid(rs.getString(2));
                        if (uuid != null) {
                            cache.put(Db.key(v), uuid);
                            reverse.put(uuid, v);
                        }
                    }
                }
            }
            for (Object v : batch) {
                cache.putIfAbsent(Db.key(v), NO_UUID);
            }
        }
    }

    /** Looks up the column values behind many uuids in a few queries. */
    void prefetchValues(Side side, Schema.Table table, String column, Collection<String> identities)
            throws SQLException {
        if (!table.hasUuid()) {
            load(side, table);
            return;
        }
        Map<String, Object> cache = uuidToValue.computeIfAbsent(cacheKey(side, table, column), k -> new HashMap<>());
        Map<String, String> reverse = valueToUuid.computeIfAbsent(cacheKey(side, table, column), k -> new HashMap<>());
        Set<String> todo = new LinkedHashSet<>();
        for (String id : identities) {
            if (id != null && !cache.containsKey(id)) {
                todo.add(id);
            }
        }
        Schema.Column col = table.column(column);
        String sql = "SELECT " + Db.q(column) + ", uuid FROM " + Db.q(table.name()) + " WHERE uuid IN (";
        for (List<String> batch : Db.batches(new ArrayList<>(todo))) {
            try (PreparedStatement ps = conns.get(side).prepareStatement(sql + Db.placeholders(batch.size()) + ")")) {
                for (int j = 0; j < batch.size(); j++) {
                    ps.setString(j + 1, batch.get(j));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Object v = Db.read(rs, 1, col);
                        String uuid = Db.normUuid(rs.getString(2));
                        cache.put(uuid, v == null ? NO_VALUE : v);
                        if (v != null) {
                            reverse.put(Db.key(v), uuid);
                        }
                    }
                }
            }
            for (String id : batch) {
                cache.putIfAbsent(id, NO_VALUE);
            }
        }
    }

    // ---------------------------------------------------------------------------------- inserts in progress

    /** Records a row just inserted into the target so later rows in the same transaction can point at it. */
    void recordInsert(Schema.Table table, String identity, Map<String, Object> targetRow) {
        pendingInserts.computeIfAbsent(table.name(), k -> new HashMap<>()).put(identity, targetRow);
    }

    /** The pending rows were committed: from now on the target's own data answers for them. */
    void commitInserts() {
        for (Map.Entry<String, Map<String, Map<String, Object>>> e : pendingInserts.entrySet()) {
            Schema.Table table = schema.table(e.getKey());
            if (table.hasUuid()) {
                forget(Side.TARGET, table); // drops cached "not in target" answers for the new rows
            } else {
                LoadedTable lt = loaded.get(loadKey(Side.TARGET, table));
                if (lt != null) {
                    e.getValue().forEach(lt::add);
                }
            }
        }
        pendingInserts.clear();
    }

    /** The pending rows were rolled back: forget them. */
    void discardInserts() {
        pendingInserts.clear();
    }

    private Map<String, Object> pendingRow(Schema.Table table, String identity) {
        return pendingInserts.getOrDefault(table.name(), Map.of()).get(identity);
    }

    // --------------------------------------------------------------------------------------------- memory

    /**
     * Drops cached lookups for rows of {@code tables}. Called once a patient is done, so the caches only grow with
     * shared data (users, providers, metadata) and not with every patient's obs and encounters. Fully loaded tables
     * without a uuid column are kept, since reloading them would cost more than they take.
     */
    void forget(Collection<Schema.Table> tables) {
        for (Schema.Table table : tables) {
            for (Side side : Side.values()) {
                forget(side, table);
            }
        }
    }

    private void forget(Side side, Schema.Table table) {
        if (!table.hasUuid()) {
            return;
        }
        for (Schema.Column column : table.columns()) {
            valueToUuid.remove(cacheKey(side, table, column.name()));
            uuidToValue.remove(cacheKey(side, table, column.name()));
        }
    }

    // ------------------------------------------------------------------------------- tables without uuid

    /** All rows of a table without a uuid column, read once per side. */
    private LoadedTable load(Side side, Schema.Table table) throws SQLException {
        String key = loadKey(side, table);
        LoadedTable lt = loaded.get(key);
        if (lt != null) {
            return lt;
        }
        lt = new LoadedTable(side, table);
        loaded.put(key, lt);
        try (PreparedStatement ps = conns.get(side).prepareStatement("SELECT * FROM " + Db.q(table.name()));
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                lt.rows.add(Db.readRow(rs, table));
            }
        }
        for (String col : table.identityColumns()) {
            Schema.ForeignKey fk = table.foreignKey(col);
            if (fk != null) {
                prefetchIdentities(side, schema.table(fk.refTable()), fk.refColumn(), columnValues(lt.rows, col));
            }
        }
        return lt;
    }

    private static Set<Object> columnValues(List<Map<String, Object>> rows, String column) {
        Set<Object> values = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            if (row.get(column) != null) {
                values.add(row.get(column));
            }
        }
        return values;
    }

    private static String cacheKey(Side side, Schema.Table table, String column) {
        return side + "|" + table.name() + "|" + column;
    }

    private static String loadKey(Side side, Schema.Table table) {
        return side + "|" + table.name();
    }

    private final class LoadedTable {
        final Side side;
        final Schema.Table table;
        final List<Map<String, Object>> rows = new ArrayList<>();
        private final Map<String, Map<String, Integer>> indexes = new HashMap<>();
        private final Map<Integer, String> identities = new HashMap<>();
        private Map<String, Integer> byIdentity;

        LoadedTable(Side side, Schema.Table table) {
            this.side = side;
            this.table = table;
        }

        Map<String, Integer> index(String column) {
            return indexes.computeIfAbsent(column, c -> {
                Map<String, Integer> idx = new HashMap<>();
                for (int i = 0; i < rows.size(); i++) {
                    String k = Db.key(rows.get(i).get(c));
                    if (k != null) {
                        idx.putIfAbsent(k, i);
                    }
                }
                return idx;
            });
        }

        String identity(int rowIndex, int depth) throws SQLException {
            String id = identities.get(rowIndex);
            if (id == null) {
                id = rowIdentity(side, table, rows.get(rowIndex), depth);
                identities.put(rowIndex, id);
            }
            return id;
        }

        Map<String, Integer> byIdentity() throws SQLException {
            if (byIdentity == null) {
                Map<String, Integer> m = new HashMap<>();
                for (int i = 0; i < rows.size(); i++) {
                    m.putIfAbsent(identity(i, 0), i);
                }
                byIdentity = m;
            }
            return byIdentity;
        }

        /** Appends a row committed after the table was loaded, keeping the indexes current. */
        void add(String identity, Map<String, Object> row) {
            int i = rows.size();
            rows.add(row);
            identities.put(i, identity);
            indexes.forEach((column, idx) -> {
                String k = Db.key(row.get(column));
                if (k != null) {
                    idx.putIfAbsent(k, i);
                }
            });
            if (byIdentity != null) {
                byIdentity.putIfAbsent(identity, i);
            }
        }
    }
}
