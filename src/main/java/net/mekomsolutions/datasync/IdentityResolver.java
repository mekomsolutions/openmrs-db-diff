package net.mekomsolutions.datasync;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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
 */
final class IdentityResolver {

    enum Side { SOURCE, TARGET }

    private static final String NO_UUID = "\u0000none";
    private static final Object NO_VALUE = new Object();
    private static final String SEP = "\u001F";
    private static final int MAX_DEPTH = 8;

    private final Schema schema;
    private final Map<Side, Connection> conns;

    /** side|table|column -> value key -> uuid (or NO_UUID). */
    private final Map<String, Map<String, String>> valueToUuid = new HashMap<>();
    /** side|table|column -> uuid -> value (or NO_VALUE). */
    private final Map<String, Map<String, Object>> uuidToValue = new HashMap<>();
    /** side|table -> fully loaded table without a uuid column. */
    private final Map<String, LoadedTable> loaded = new HashMap<>();
    /** table -> identity -> row as written to the target during this run. */
    private final Map<String, Map<String, Map<String, Object>>> insertedIntoTarget = new HashMap<>();

    IdentityResolver(Schema schema, Connection source, Connection target) {
        this.schema = schema;
        this.conns = Map.of(Side.SOURCE, source, Side.TARGET, target);
    }

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
        if (side == Side.TARGET) {
            Map<String, Object> row = insertedIntoTarget.getOrDefault(table.name(), Map.of()).get(identity);
            if (row != null) {
                return row.get(column);
            }
        }
        if (table.hasUuid()) {
            if (column.equals("uuid")) {
                // Still has to exist on this side.
                Object pkValue = valueFor(side, table, table.primaryKey().isEmpty() ? "uuid" : table.primaryKey().get(0), identity);
                return pkValue == null ? null : identity;
            }
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

    /** Identity of a full row read from {@code side}. */
    String rowIdentity(Side side, Schema.Table table, Map<String, Object> row) throws SQLException {
        return rowIdentity(side, table, row, 0);
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
        List<Object> pending = new ArrayList<>(todo.values());
        Schema.Column col = table.column(column);
        for (int i = 0; i < pending.size(); i += Db.BATCH) {
            List<Object> batch = pending.subList(i, Math.min(pending.size(), i + Db.BATCH));
            String sql = "SELECT " + Db.q(column) + ", uuid FROM " + Db.q(table.name()) + " WHERE " + Db.q(column)
                    + " IN (" + Db.placeholders(batch.size()) + ")";
            try (PreparedStatement ps = conns.get(side).prepareStatement(sql)) {
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
        if (column.equals("uuid")) {
            column = table.primaryKey().isEmpty() ? "uuid" : table.primaryKey().get(0);
            if (column.equals("uuid")) {
                return;
            }
        }
        Map<String, Object> cache = uuidToValue.computeIfAbsent(cacheKey(side, table, column), k -> new HashMap<>());
        Map<String, String> reverse = valueToUuid.computeIfAbsent(cacheKey(side, table, column), k -> new HashMap<>());
        Set<String> todo = new LinkedHashSet<>();
        for (String id : identities) {
            if (id != null && !cache.containsKey(id)) {
                todo.add(id);
            }
        }
        List<String> pending = new ArrayList<>(todo);
        Schema.Column col = table.column(column);
        for (int i = 0; i < pending.size(); i += Db.BATCH) {
            List<String> batch = pending.subList(i, Math.min(pending.size(), i + Db.BATCH));
            String sql = "SELECT " + Db.q(column) + ", uuid FROM " + Db.q(table.name()) + " WHERE uuid IN ("
                    + Db.placeholders(batch.size()) + ")";
            try (PreparedStatement ps = conns.get(side).prepareStatement(sql)) {
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

    /** Records a row just inserted into the target so later rows can point at it. */
    void registerInserted(Schema.Table table, String identity, Map<String, Object> targetRow) {
        insertedIntoTarget.computeIfAbsent(table.name(), k -> new HashMap<>()).put(identity, targetRow);
    }

    /** All rows of a table without a uuid column, read once per side. */
    LoadedTable load(Side side, Schema.Table table) throws SQLException {
        String key = side + "|" + table.name();
        LoadedTable lt = loaded.get(key);
        if (lt != null) {
            return lt;
        }
        lt = new LoadedTable(side, table);
        loaded.put(key, lt);
        try (PreparedStatement ps = conns.get(side).prepareStatement("SELECT * FROM " + Db.q(table.name()));
             ResultSet rs = ps.executeQuery()) {
            List<Schema.Column> cols = new ArrayList<>(table.columns());
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < cols.size(); i++) {
                    row.put(cols.get(i).name(), Db.read(rs, rs.findColumn(cols.get(i).name()), cols.get(i)));
                }
                lt.rows.add(row);
            }
        }
        for (String col : table.identityColumns()) {
            Schema.ForeignKey fk = table.foreignKey(col);
            if (fk != null) {
                Set<Object> values = new LinkedHashSet<>();
                for (Map<String, Object> row : lt.rows) {
                    if (row.get(col) != null) {
                        values.add(row.get(col));
                    }
                }
                prefetchIdentities(side, schema.table(fk.refTable()), fk.refColumn(), values);
            }
        }
        return lt;
    }

    private static String cacheKey(Side side, Schema.Table table, String column) {
        return side + "|" + table.name() + "|" + column;
    }

    final class LoadedTable {
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

        String identity(int rowIndex) throws SQLException {
            return identity(rowIndex, 0);
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
                    m.putIfAbsent(identity(i), i);
                }
                byIdentity = m;
            }
            return byIdentity;
        }
    }
}
