package net.mekomsolutions.dbdiff;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Table, column, primary key and foreign key metadata of one MySQL schema, read from information_schema.
 */
public final class Schema {

    public record Column(String name, String dataType, boolean nullable, boolean autoIncrement) {

        boolean isTemporal() {
            return Set.of("date", "datetime", "timestamp", "time", "year").contains(dataType);
        }

        boolean isBinary() {
            return Set.of("blob", "tinyblob", "mediumblob", "longblob", "binary", "varbinary").contains(dataType);
        }
    }

    public record ForeignKey(String column, String refTable, String refColumn) {}

    public static final class Table {
        private final String name;
        private final Map<String, Column> columns = new LinkedHashMap<>();
        private final List<String> primaryKey = new ArrayList<>();
        private final Map<String, ForeignKey> foreignKeys = new LinkedHashMap<>();

        Table(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }

        public Collection<Column> columns() {
            return columns.values();
        }

        public Column column(String col) {
            return columns.get(col);
        }

        public boolean hasColumn(String col) {
            return columns.containsKey(col);
        }

        public List<String> primaryKey() {
            return primaryKey;
        }

        public Collection<ForeignKey> foreignKeys() {
            return foreignKeys.values();
        }

        public ForeignKey foreignKey(String col) {
            return foreignKeys.get(col);
        }

        public boolean hasUuid() {
            return columns.containsKey("uuid");
        }

        /**
         * Columns that identify a row across databases when the table has no uuid column: the primary key when
         * none of its columns are auto-increment (for example patient.patient_id or user_role's (user_id, role)),
         * otherwise every non auto-increment column.
         */
        public List<String> identityColumns() {
            boolean pkUsable = !primaryKey.isEmpty()
                    && primaryKey.stream().noneMatch(c -> columns.get(c).autoIncrement());
            if (pkUsable) {
                return primaryKey;
            }
            return columns.values().stream().filter(c -> !c.autoIncrement()).map(Column::name).toList();
        }

        @Override
        public String toString() {
            return name;
        }

        void addColumn(Column column) {
            columns.put(column.name(), column);
        }

        void addPrimaryKeyColumn(String column) {
            primaryKey.add(column);
        }

        void addForeignKey(ForeignKey fk) {
            foreignKeys.putIfAbsent(fk.column(), fk);
        }
    }

    private final Map<String, Table> tables = new LinkedHashMap<>();

    public Collection<Table> tables() {
        return tables.values();
    }

    public Table table(String name) {
        return tables.get(name);
    }

    /** Adds an empty table. {@link #read} builds schemas this way; tests use it to build them from other databases. */
    Table addTable(String name) {
        return tables.computeIfAbsent(name, Table::new);
    }

    public static Schema read(Connection conn) throws SQLException {
        Schema schema = new Schema();
        try (ResultSet rs = query(conn, "SELECT TABLE_NAME FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME")) {
            while (rs.next()) {
                schema.addTable(rs.getString(1));
            }
        }
        try (ResultSet rs = query(conn, "SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, IS_NULLABLE, EXTRA "
                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "ORDER BY TABLE_NAME, ORDINAL_POSITION")) {
            while (rs.next()) {
                Table t = schema.tables.get(rs.getString(1));
                if (t == null) {
                    continue; // a view
                }
                String extra = rs.getString(5) == null ? "" : rs.getString(5).toLowerCase(Locale.ROOT);
                t.addColumn(new Column(rs.getString(2),
                        rs.getString(3).toLowerCase(Locale.ROOT),
                        "YES".equals(rs.getString(4)),
                        extra.contains("auto_increment")));
            }
        }
        try (ResultSet rs = query(conn, "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE "
                + "WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'PRIMARY' "
                + "ORDER BY TABLE_NAME, ORDINAL_POSITION")) {
            while (rs.next()) {
                Table t = schema.tables.get(rs.getString(1));
                if (t != null) {
                    t.addPrimaryKeyColumn(rs.getString(2));
                }
            }
        }
        // OpenMRS foreign keys are single-column; composite ones are skipped with a warning.
        Map<String, List<String[]>> byConstraint = new LinkedHashMap<>();
        try (ResultSet rs = query(conn, "SELECT TABLE_NAME, CONSTRAINT_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME, "
                + "REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE "
                + "WHERE TABLE_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL "
                + "ORDER BY TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION")) {
            while (rs.next()) {
                byConstraint.computeIfAbsent(rs.getString(1) + "." + rs.getString(2), k -> new ArrayList<>())
                        .add(new String[] {rs.getString(1), rs.getString(3), rs.getString(4), rs.getString(5)});
            }
        }
        for (Map.Entry<String, List<String[]>> e : byConstraint.entrySet()) {
            if (e.getValue().size() > 1) {
                Log.warn("Skipping composite foreign key " + e.getKey());
                continue;
            }
            String[] fk = e.getValue().get(0);
            Table t = schema.tables.get(fk[0]);
            if (t != null && schema.tables.containsKey(fk[2])) {
                t.addForeignKey(new ForeignKey(fk[1], fk[2], fk[3]));
            }
        }
        return schema;
    }

    private static ResultSet query(Connection conn, String sql) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(sql);
        ps.closeOnCompletion();
        return ps.executeQuery();
    }
}
