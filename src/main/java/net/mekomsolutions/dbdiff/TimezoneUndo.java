package net.mekomsolutions.dbdiff;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import net.mekomsolutions.dbdiff.TimeShift.Change;

/**
 * timezone --undo: puts back the old values recorded in {@value ShiftLog#TABLE}, for the tables given and/or one
 * run id, and removes their ledger rows, chunk by chunk, one transaction each. A row is only restored if it still holds the value the shift wrote; otherwise it
 * is left as it is, reported as CHANGED_SINCE, and its ledger row kept. Without --apply, only lists what it would do.
 */
final class TimezoneUndo {

    private record Entry(String table, String rowKey, String runId, List<Change> changes) {}

    private final Options options;
    private final SyncConfig config;
    private final SyncRun.SchemaReader schemaReader;

    TimezoneUndo(Options options, SyncConfig config) {
        this(options, config, Schema::read);
    }

    TimezoneUndo(Options options, SyncConfig config, SyncRun.SchemaReader schemaReader) {
        this.options = options;
        this.config = config;
        this.schemaReader = schemaReader;
    }

    /** The ledger's text form of the changes of one row: {@code column: old -> new; ...}. */
    static String formatChanges(List<Change> changes) {
        return changes.stream().map(c -> c.column() + ": " + c.oldValue() + " -> " + c.newValue())
                .collect(Collectors.joining("; "));
    }

    static List<Change> parseChanges(String text) {
        List<Change> changes = new ArrayList<>();
        for (String part : text.split("; ")) {
            int colon = part.indexOf(": ");
            int arrow = part.indexOf(" -> ");
            if (colon < 0 || arrow < colon) {
                throw new IllegalArgumentException("Unreadable ledger entry: " + text);
            }
            changes.add(new Change(part.substring(0, colon).trim(), part.substring(colon + 2, arrow).trim(),
                    part.substring(arrow + 4).trim()));
        }
        return changes;
    }

    int execute() throws SQLException, IOException {
        boolean apply = options.mode() == RunMode.TIMEZONE_APPLY;
        Options.Timezone tz = options.timezone();
        SyncConfig.DbSettings settings = tz.database().equals("source") ? config.source() : config.target();
        Log.info("Database to restore: " + settings.label() + " " + settings.url());
        try (Connection conn = settings.connect()) {
            conn.setReadOnly(!apply);
            if (!ShiftLog.exists(conn)) {
                Log.info("No " + ShiftLog.TABLE + " table: nothing was shifted here");
                return 0;
            }
            Schema schema = schemaReader.read(conn);
            List<String> tables = tz.allTables() ? List.of()
                    : tz.tables().stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
            if (tz.runId() != null && !ShiftLog.hasColumn(conn, "run_id")) {
                Log.info("The ledger has no run_id column yet: no run can match " + tz.runId());
                return 0;
            }
            String filter = (tables.isEmpty() ? "" : " AND LOWER(table_name) IN (" + Db.placeholders(tables.size())
                    + ")") + (tz.runId() == null ? "" : " AND run_id = ?");
            Log.info((apply ? "Restoring" : "Dry run (nothing is written): would restore") + " rows of "
                    + (tables.isEmpty() ? "every table" : String.join(", ", tables))
                    + (tz.runId() == null ? "" : " shifted by run " + tz.runId()));
            if (apply && !options.assumeYes() && !confirm(settings)) {
                Log.info("Cancelled");
                return 1;
            }
            Path dir = config.outputDir().resolve(ShiftLog.newRunId() + "-timezone-undo");
            Files.createDirectories(dir);
            long restored = 0;
            long changedSince = 0;
            String lastTable = "";
            String lastKey = "";
            try (CsvWriter csv = new CsvWriter(dir.resolve("undo.csv"), List.of("table", "row_key", "run_id",
                    "column", "current_value", "restored_value", "status"))) {
                while (true) {
                    List<Entry> chunk = readChunk(conn, filter, tables, tz, lastTable, lastKey);
                    if (chunk.isEmpty()) {
                        break;
                    }
                    lastTable = chunk.get(chunk.size() - 1).table();
                    lastKey = chunk.get(chunk.size() - 1).rowKey();
                    if (apply) {
                        conn.setAutoCommit(false);
                    }
                    try {
                        for (Entry e : chunk) {
                            Schema.Table table = schema.table(e.table());
                            if (table == null || table.primaryKey().isEmpty()) {
                                throw new SQLException("Cannot restore " + e.table() + " " + e.rowKey()
                                        + ": no such table, or no primary key");
                            }
                            boolean ok = !apply ? stillShifted(conn, table, e) : restore(conn, table, e);
                            if (ok) {
                                restored++;
                            } else {
                                changedSince++;
                            }
                            String status = ok ? (apply ? "RESTORED" : "WOULD_RESTORE") : "CHANGED_SINCE";
                            for (Change c : e.changes()) {
                                csv.row(List.of(e.table(), e.rowKey(), e.runId() == null ? "" : e.runId(),
                                        c.column(), c.newValue(), c.oldValue(), status));
                            }
                        }
                        if (apply) {
                            conn.commit();
                        }
                    } catch (SQLException | RuntimeException ex) {
                        if (apply) {
                            conn.rollback();
                        }
                        throw ex;
                    } finally {
                        if (apply) {
                            conn.setAutoCommit(true);
                        }
                    }
                    Log.info(String.format("  %d rows %s, %d changed since the shift (left alone)", restored,
                            apply ? "restored" : "to restore", changedSince));
                }
            }
            Log.info("Report written to " + dir.toAbsolutePath());
            if (!apply) {
                Log.info("Nothing was written. Re-run with --apply to restore.");
            }
            return 0;
        }
    }

    private static List<Entry> readChunk(Connection conn, String filter, List<String> tables, Options.Timezone tz,
                                         String lastTable, String lastKey) throws SQLException {
        String sql = "SELECT table_name, row_key, changes" + (tz.runId() == null ? ", NULL" : ", run_id") + " FROM "
                + ShiftLog.TABLE + " WHERE (table_name > ? OR (table_name = ? AND row_key > ?))" + filter
                + " ORDER BY table_name, row_key LIMIT ?";
        List<Entry> entries = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, lastTable);
            ps.setString(i++, lastTable);
            ps.setString(i++, lastKey);
            for (String t : tables) {
                ps.setString(i++, t);
            }
            if (tz.runId() != null) {
                ps.setString(i++, tz.runId());
            }
            ps.setInt(i, tz.chunkSize());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new Entry(rs.getString(1), rs.getString(2), rs.getString(4),
                            parseChanges(rs.getString(3))));
                }
            }
        }
        return entries;
    }

    /** Restores one row and drops its ledger row; false when the row no longer holds the shifted values. */
    private static boolean restore(Connection conn, Schema.Table table, Entry e) throws SQLException {
        String sql = "UPDATE " + Db.q(table.name()) + " SET "
                + e.changes().stream().map(c -> Db.q(c.column()) + " = ?").collect(Collectors.joining(", "))
                + where(table, e);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            for (Change c : e.changes()) {
                ps.setObject(i++, value(c.oldValue()));
            }
            bindWhere(ps, i, table, e);
            if (ps.executeUpdate() != 1) {
                return false;
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + ShiftLog.TABLE
                + " WHERE table_name = ? AND row_key = ?")) {
            ps.setString(1, e.table());
            ps.setString(2, e.rowKey());
            ps.executeUpdate();
        }
        return true;
    }

    private static boolean stillShifted(Connection conn, Schema.Table table, Entry e) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM " + Db.q(table.name()) + where(table, e))) {
            bindWhere(ps, 1, table, e);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String where(Schema.Table table, Entry e) {
        return " WHERE " + table.primaryKey().stream().map(pk -> Db.q(pk) + " = ?").collect(Collectors.joining(" AND "))
                + e.changes().stream().map(c -> " AND " + Db.q(c.column()) + " = ?").collect(Collectors.joining());
    }

    private static void bindWhere(PreparedStatement ps, int start, Schema.Table table, Entry e) throws SQLException {
        String[] keys = e.rowKey().split("/", -1);
        if (keys.length != table.primaryKey().size()) {
            throw new SQLException("Row key " + e.rowKey() + " does not fit the primary key of " + table.name());
        }
        int i = start;
        for (String key : keys) {
            ps.setString(i++, key);
        }
        for (Change c : e.changes()) {
            ps.setObject(i++, value(c.newValue()));
        }
    }

    private static LocalDateTime value(String text) {
        LocalDateTime t = TimeShift.parse(text);
        if (t == null) {
            throw new IllegalArgumentException("Unreadable datetime in the ledger: " + text);
        }
        return t;
    }

    private static boolean confirm(SyncConfig.DbSettings settings) throws IOException {
        System.out.print("About to restore shifted datetimes in " + settings.label() + " (" + settings.url()
                + "), committing chunk by chunk. Type APPLY to continue: ");
        System.out.flush();
        String answer = new BufferedReader(new InputStreamReader(System.in)).readLine();
        return "APPLY".equals(answer == null ? null : answer.trim());
    }
}
