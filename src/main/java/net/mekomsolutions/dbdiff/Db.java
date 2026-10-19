package net.mekomsolutions.dbdiff;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Small JDBC helpers shared by the lookups, the reads and the sync. */
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

    /** Reads every column of {@code table} from the current row, keyed by column name in table order. */
    static Map<String, Object> readRow(ResultSet rs, Schema.Table table) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (Schema.Column column : table.columns()) {
            row.put(column.name(), read(rs, rs.findColumn(column.name()), column));
        }
        return row;
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
        return String.join(",", Collections.nCopies(n, "?"));
    }

    /** Splits {@code items} into consecutive chunks of at most {@link #BATCH} elements. */
    static <T> List<List<T>> batches(List<T> items) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < items.size(); i += BATCH) {
            batches.add(items.subList(i, Math.min(items.size(), i + BATCH)));
        }
        return batches;
    }
}
