package net.mekomsolutions.dbdiff;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The {@value #TABLE} table the timezone command keeps in the database it converts: one row per converted row, with
 * its old and new values and the run that converted it. It keeps a row from being converted twice and is what
 * timezone --undo restores from.
 */
final class ShiftLog {

    static final String TABLE = "dbdiff_timezone_shift";

    private ShiftLog() {}

    /**
     * Creates the table, or adds the run_id column to one made by an older version. The column is nullable, so rows
     * logged before it existed stay valid (they can only be undone by table).
     */
    static void create(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "table_name VARCHAR(64) NOT NULL, "
                    + "row_key VARCHAR(255) NOT NULL, "
                    + "patient_uuid CHAR(38) NOT NULL, "
                    + "changes TEXT NOT NULL, "
                    + "shifted_at DATETIME NOT NULL, "
                    + "run_id VARCHAR(64) NULL, "
                    + "PRIMARY KEY (table_name, row_key))");
        }
        if (!hasColumn(conn, "run_id")) {
            try (Statement s = conn.createStatement()) {
                s.execute("ALTER TABLE " + TABLE + " ADD COLUMN run_id VARCHAR(64) NULL");
            }
        }
    }

    static boolean exists(Connection conn) {
        return hasColumn(conn, "1");
    }

    static boolean hasColumn(Connection conn, String column) {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT " + column + " FROM " + TABLE + " WHERE 1 = 0")) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /** A new run id: the time the run started, to the millisecond, as in the report folder name. */
    static String newRunId() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
    }
}
