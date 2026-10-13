package net.mekomsolutions.datasync;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Locale;

/** Small JDBC helpers shared by the diff and the sync. */
final class Db {

    /** Largest IN (...) list sent in one query. */
    static final int BATCH = 1000;

    private Db() {}

    static String q(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    /**
     * Reads a column so it can be written back unchanged: temporal values as text (no time zone shifts and zero
     * dates survive), binary values as bytes, everything else as the driver's object.
     */
    static Object read(ResultSet rs, int index, Schema.Column column) throws SQLException {
        if (column.isTemporal()) {
            return rs.getString(index);
        }
        if (column.isBinary()) {
            return rs.getBytes(index);
        }
        return rs.getObject(index);
    }

    /** Stable text form of a value, used as a cache and comparison key. */
    static String key(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return "0x" + HexFormat.of().formatHex(bytes);
        }
        if (value instanceof Boolean b) {
            return b ? "1" : "0";
        }
        return value.toString();
    }

    static String normUuid(Object uuid) {
        return uuid == null ? null : uuid.toString().trim().toLowerCase(Locale.ROOT);
    }

    static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    /** A forward-only statement that streams rows instead of buffering the whole result in memory. */
    static PreparedStatement streaming(Connection conn, String sql) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        ps.setFetchSize(Integer.MIN_VALUE);
        return ps;
    }
}
