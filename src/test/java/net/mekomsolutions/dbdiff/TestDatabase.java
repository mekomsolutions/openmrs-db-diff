package net.mekomsolutions.dbdiff;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * An in-memory H2 database in MySQL mode, loaded from the SQL fixtures. Stands in for one OpenMRS database so the
 * sync can be tested without Docker. Every instance is a fresh, uniquely named database.
 */
final class TestDatabase implements AutoCloseable {

    private final String url;
    private final Connection keepAlive;

    private TestDatabase(String url) throws SQLException {
        this.url = url;
        this.keepAlive = DriverManager.getConnection(url);
    }

    /** A database with the fixture schema and the given fixture data files (from src/test/resources/fixtures). */
    static TestDatabase withFixtures(String... dataFiles) throws SQLException {
        TestDatabase db = new TestDatabase("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        db.runFixture("schema.sql");
        for (String file : dataFiles) {
            db.runFixture(file);
        }
        return db;
    }

    String url() {
        return url;
    }

    Connection connect() throws SQLException {
        return DriverManager.getConnection(url);
    }

    void execute(String... statements) throws SQLException {
        try (Statement s = keepAlive.createStatement()) {
            for (String sql : statements) {
                s.execute(sql);
            }
        }
    }

    /** The first column of the first row of {@code sql}, as text, or null when there is no row. */
    String queryString(String sql) throws SQLException {
        try (Statement s = keepAlive.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    long count(String table) throws SQLException {
        return Long.parseLong(queryString("SELECT COUNT(*) FROM " + table));
    }

    /**
     * The schema as {@link Schema#read} would see it on MySQL, built from JDBC metadata since H2 has its own
     * information_schema layout.
     */
    Schema schema() throws SQLException {
        Schema schema = new Schema();
        DatabaseMetaData meta = keepAlive.getMetaData();
        List<String> names = new ArrayList<>();
        try (ResultSet rs = meta.getTables(null, "public", "%", new String[] {"TABLE", "BASE TABLE"})) {
            while (rs.next()) {
                names.add(rs.getString("TABLE_NAME"));
            }
        }
        names.sort(null);
        for (String name : names) {
            Schema.Table table = schema.addTable(name);
            try (ResultSet rs = meta.getColumns(null, "public", name, "%")) {
                while (rs.next()) {
                    // H2 turns MySQL's DATETIME into TIMESTAMP
                    String type = rs.getString("TYPE_NAME").toLowerCase(Locale.ROOT);
                    table.addColumn(new Schema.Column(rs.getString("COLUMN_NAME"),
                            type.equals("timestamp") ? "datetime" : type,
                            "YES".equals(rs.getString("IS_NULLABLE")),
                            "YES".equals(rs.getString("IS_AUTOINCREMENT"))));
                }
            }
            List<String[]> pk = new ArrayList<>();
            try (ResultSet rs = meta.getPrimaryKeys(null, "public", name)) {
                while (rs.next()) {
                    pk.add(new String[] {rs.getString("KEY_SEQ"), rs.getString("COLUMN_NAME")});
                }
            }
            pk.sort((a, b) -> Integer.compare(Integer.parseInt(a[0]), Integer.parseInt(b[0])));
            pk.forEach(c -> table.addPrimaryKeyColumn(c[1]));
        }
        for (String name : names) {
            try (ResultSet rs = meta.getImportedKeys(null, "public", name)) {
                while (rs.next()) {
                    schema.table(name).addForeignKey(new Schema.ForeignKey(rs.getString("FKCOLUMN_NAME"),
                            rs.getString("PKTABLE_NAME"), rs.getString("PKCOLUMN_NAME")));
                }
            }
        }
        return schema;
    }

    private void runFixture(String file) throws SQLException {
        String sql;
        try (InputStream in = TestDatabase.class.getResourceAsStream("/fixtures/" + file)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + file);
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (String statement : sql.replaceAll("(?m)^--.*$", "").split(";")) {
            if (!statement.isBlank()) {
                execute(statement);
            }
        }
    }

    @Override
    public void close() throws SQLException {
        try (Statement s = keepAlive.createStatement()) {
            s.execute("SHUTDOWN");
        }
    }
}
