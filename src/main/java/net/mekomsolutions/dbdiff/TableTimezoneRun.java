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
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import net.mekomsolutions.dbdiff.TimezoneTables.Candidate;
import net.mekomsolutions.dbdiff.TimezoneTables.ExcludedKeys;
import net.mekomsolutions.dbdiff.TimezoneTables.Placeholders;

/**
 * The timezone command, table by table: converts the DATETIME values of every row whose {@code date_created} falls in
 * one range [--from, --before), on one database, without going through patients.
 *
 * <p>In a qualifying row all DATETIME columns are converted together, in one UPDATE, except skipped columns and any
 * value at or after --before (written after the change, already in the new convention). Values are read and written
 * as {@link LocalDateTime}, so neither the driver nor the server time zone moves them. These rows are left alone:
 * rows in an --exclude-keys range, placeholder rows (the placeholder uuid, any value on 1970-01-01, or a foreign key
 * to a placeholder parent, see {@link Placeholders}), and rows already in {@value ShiftLog#TABLE}. Going to UTC, a
 * local time that happened twice (clocks going back) takes the earlier offset and is listed in dst-ambiguous.csv; a
 * local time that never happened (clocks going forward) is left unchanged and listed in dst-gap.csv.
 *
 * <p>With --apply, rows are written in chunks of --chunk-size, one transaction each, together with their old values
 * in the ledger; a re-run skips them.
 */
final class TableTimezoneRun {

    /** Start and end columns that must stay in order; flagged when a conversion puts the end first. */
    static final List<List<String>> END_PAIRS = List.of(
            List.of("date_started", "date_stopped"),
            List.of("date_started", "date_ended"),
            List.of("date_activated", "date_stopped"),
            List.of("date_activated", "auto_expire_date"),
            List.of("start_date", "end_date"),
            List.of("start_datetime", "end_datetime"),
            List.of("date_enrolled", "date_completed"));

    /** A row changed or voided at or after --before with one of these was edited after the change of convention. */
    static final List<String> EDIT_COLUMNS = List.of("date_changed", "date_voided", "date_retired");

    /** One row read from a table. */
    private record Row(long key, String uuid, Map<String, LocalDateTime> values, Set<String> unreadable,
                       Map<String, Long> refs) {}

    /** One value to convert. */
    private record Value(String column, LocalDateTime oldValue, LocalDateTime newValue) {}

    private record RowChange(Row row, List<Value> values) {}

    /** Counts for one column. */
    private static final class ColumnStats {
        long changed;
        long postMigration;
        long midnight;
        long ambiguous;
        long gap;
        long unreadable;
        final long[] before = new long[24];
        final long[] after = new long[24];
    }

    /** Counts for one table. */
    private static final class TableStats {
        long rowsInRange;
        long rowsToChange;
        long valuesToChange;
        long siteKeys;
        long placeholders;
        long alreadyShifted;
        long endBeforeStart;
        long edited;
        long nullAnchor;
        final Map<String, ColumnStats> columns = new LinkedHashMap<>();
    }

    /** The report files of a run. */
    private record Reports(CsvWriter changes, CsvWriter tables, CsvWriter columns, CsvWriter histogram,
                           CsvWriter endBeforeStart, CsvWriter ambiguous, CsvWriter gap, CsvWriter edited,
                           CsvWriter nullAnchor) implements AutoCloseable {

        static Reports open(Path dir) throws IOException {
            return new Reports(
                    new CsvWriter(dir.resolve("changes.csv"),
                            List.of("table", "pk", "uuid", "column", "old_value", "new_value")),
                    new CsvWriter(dir.resolve("tables.csv"), List.of("table", "status", "rows_in_range",
                            "rows_to_change", "values_to_change", "skipped_site_keys", "skipped_placeholder",
                            "already_shifted", "end_before_start", "skipped_edited", "null_anchor", "detail")),
                    new CsvWriter(dir.resolve("columns.csv"), List.of("table", "column", "values_changed",
                            "skipped_post_migration", "midnight_values", "ambiguous", "gap", "unreadable")),
                    new CsvWriter(dir.resolve("hour-histogram.csv"),
                            List.of("table", "column", "hour", "before", "after")),
                    new CsvWriter(dir.resolve("end-before-start.csv"), List.of("table", "pk", "uuid", "start_column",
                            "end_column", "old_start", "old_end", "new_start", "new_end", "note")),
                    new CsvWriter(dir.resolve("dst-ambiguous.csv"),
                            List.of("table", "pk", "uuid", "column", "value", "converted_with_earlier_offset")),
                    new CsvWriter(dir.resolve("dst-gap.csv"),
                            List.of("table", "pk", "uuid", "column", "value_left_unchanged")),
                    new CsvWriter(dir.resolve("edited-after-cutoff.csv"),
                            List.of("table", "pk", "uuid", "edited_column", "edited_value", "current_values")),
                    new CsvWriter(dir.resolve("null-date-created.csv"), List.of("table", "pk", "uuid", "column")));
        }

        @Override
        public void close() throws IOException {
            for (CsvWriter w : List.of(changes, tables, columns, histogram, endBeforeStart, ambiguous, gap, edited,
                    nullAnchor)) {
                w.close();
            }
        }
    }

    private final Options options;
    private final SyncConfig config;
    private final SyncRun.SchemaReader schemaReader;

    TableTimezoneRun(Options options, SyncConfig config) {
        this(options, config, Schema::read);
    }

    TableTimezoneRun(Options options, SyncConfig config, SyncRun.SchemaReader schemaReader) {
        this.options = options;
        this.config = config;
        this.schemaReader = schemaReader;
    }

    /** Runs and returns the process exit code: 0 when no table failed, 1 otherwise. */
    int execute() throws SQLException, IOException {
        boolean apply = options.mode() == RunMode.TIMEZONE_APPLY;
        Options.Timezone tz = options.timezone();
        SyncConfig.DbSettings settings = tz.database().equals("source") ? config.source() : config.target();
        Log.info("Database to fix: " + settings.label() + " " + settings.url());
        try (Connection conn = settings.connect()) {
            conn.setReadOnly(!apply);
            Schema schema = schemaReader.read(conn);
            TimeShift shift = new TimeShift(tz.zone(), tz.toUtc());
            ExcludedKeys keys = ExcludedKeys.load(tz.excludeKeysFile());
            List<Candidate> candidates = TimezoneTables.select(schema, config, tz.tables(), tz.allTables(),
                    tz.anchors());
            Log.info("Rows with date_created in [" + tz.window() + ") move " + (tz.toUtc() ? "from " + tz.zone()
                    + " time to UTC" : "from UTC to " + tz.zone() + " time") + "; never converted: "
                    + (tz.skipped().isEmpty() ? "(none)" : String.join(", ", tz.skipped())));
            Log.info((apply ? "Converting, committing every " + tz.chunkSize() + " rows," : "Dry run (nothing is"
                    + " written)") + " in " + candidates.stream().filter(Candidate::usable).count() + " table(s)");
            if (apply && !options.assumeYes() && !confirm(candidates, settings)) {
                Log.info("Cancelled");
                return 1;
            }
            if (apply) {
                ShiftLog.create(conn);
            }
            boolean ledger = apply || ShiftLog.exists(conn);
            String runId = ShiftLog.newRunId();
            if (apply) {
                Log.info("Run id (for timezone --undo --run-id): " + runId);
            }
            Path dir = config.outputDir().resolve(runId + "-timezone-tables");
            Files.createDirectories(dir);
            Placeholders placeholders = new Placeholders(conn, schema, config.placeholderUuid());
            boolean failed = false;
            List<String> warnings = new ArrayList<>();
            try (Reports reports = Reports.open(dir)) {
                for (Candidate c : candidates) {
                    String name = c.table().name();
                    if (!c.usable()) {
                        Log.info(name + ": skipped, " + c.problem());
                        reports.tables().row(List.of(name, "SKIPPED", "", "", "", "", "", "", "", "", "", c.problem()));
                        if (c.problem().startsWith(TimezoneTables.NO_ANCHOR)) {
                            warnings.add(name + ": not converted, " + c.problem());
                        }
                        continue;
                    }
                    TableStats stats = new TableStats();
                    String status;
                    String detail = "";
                    try {
                        convert(conn, c, shift, tz, keys, placeholders, ledger, apply, runId, stats, reports);
                        status = stats.rowsToChange == 0 ? "NOTHING_TO_CHANGE" : apply ? "CHANGED" : "WOULD_CHANGE";
                    } catch (SQLException | RuntimeException e) {
                        failed = true;
                        status = "FAILED";
                        detail = e.getMessage();
                        Log.warn(name + ": " + e.getMessage());
                    }
                    report(reports, name, status, detail, stats);
                    if (stats.edited > 0) {
                        warnings.add(name + ": " + stats.edited + " row(s) edited on or after --before left as they"
                                + " are (edited-after-cutoff.csv; --convert-edited converts them)");
                    }
                    if (stats.nullAnchor > 0) {
                        warnings.add(name + ": " + stats.nullAnchor + " row(s) with no " + c.anchor()
                                + " not converted (null-date-created.csv)");
                    }
                    Log.info(String.format("%s: %s, %d rows in range, %d to change (%d values), site keys %d,"
                                    + " placeholders %d, already shifted %d, end before start %d", name, status,
                            stats.rowsInRange, stats.rowsToChange, stats.valuesToChange, stats.siteKeys,
                            stats.placeholders, stats.alreadyShifted, stats.endBeforeStart)
                            + (stats.edited > 0 ? ", edited after cutoff " + stats.edited : ""));
                    if (status.equals("FAILED") && !conn.isValid(5)) {
                        throw new SQLException("Lost the database connection during " + name + "; stopping."
                                + " Committed chunks stay committed and are skipped on a re-run.");
                    }
                }
            }
            warnings.forEach(w -> Log.warn(w));
            Log.info("Report written to " + dir.toAbsolutePath());
            if (!apply) {
                Log.info("Nothing was written. Check changes.csv, columns.csv and hour-histogram.csv, then re-run"
                        + " with --apply.");
            }
            return failed ? 1 : 0;
        }
    }

    private static void convert(Connection conn, Candidate c, TimeShift shift, Options.Timezone tz, ExcludedKeys keys,
                                Placeholders placeholders, boolean ledger, boolean apply, String runId,
                                TableStats stats, Reports reports) throws SQLException, IOException {
        Schema.Table table = c.table();
        String name = table.name();
        String pk = c.pk();
        List<String> datetimes = TimezoneTables.datetimeColumns(table).stream().map(Schema.Column::name).toList();
        List<String> converted = datetimes.stream()
                .filter(col -> !tz.skipped().stream().anyMatch(s -> s.equalsIgnoreCase(name + "." + col))).toList();
        converted.forEach(col -> stats.columns.put(col, new ColumnStats()));
        if (tz.excludeKeysFile() != null && keys.of(name).isEmpty()) {
            Log.warn(name + ": no key range in " + tz.excludeKeysFile() + "; every qualifying row is converted");
        }
        Map<String, Set<Long>> parentKeys = new LinkedHashMap<>();
        for (Map.Entry<String, Schema.ForeignKey> e : placeholders.foreignKeys(table).entrySet()) {
            Set<Long> parents = placeholders.parents(e.getValue().refTable());
            if (!parents.isEmpty()) {
                parentKeys.put(e.getKey(), parents);
            }
        }
        if (!parentKeys.isEmpty()) {
            Log.info(name + ": placeholder parents via " + parentKeys.entrySet().stream()
                    .map(e -> e.getKey() + " (" + e.getValue().size() + ")").collect(Collectors.joining(", ")));
        }
        Set<String> columns = new LinkedHashSet<>();
        columns.add(pk);
        if (table.hasUuid()) {
            columns.add("uuid");
        }
        columns.addAll(datetimes);
        columns.addAll(parentKeys.keySet());
        String sql = "SELECT " + columns.stream().map(Db::q).collect(Collectors.joining(", ")) + " FROM " + Db.q(name)
                + " WHERE " + Db.q(pk) + " > ? AND " + Db.q(c.anchor()) + " < ?"
                + (tz.from() == null ? "" : " AND " + Db.q(c.anchor()) + " >= ?")
                + " ORDER BY " + Db.q(pk) + " LIMIT ?";
        long last = Long.MIN_VALUE;
        while (true) {
            List<Row> rows = read(conn, sql, table, last, tz, datetimes, parentKeys.keySet());
            if (rows.isEmpty()) {
                break;
            }
            last = rows.get(rows.size() - 1).key();
            Set<Long> done = ledger ? alreadyShifted(conn, name, rows) : Set.of();
            List<RowChange> todo = new ArrayList<>();
            for (Row row : rows) {
                stats.rowsInRange++;
                if (keys.find(name, row.key()) != null) {
                    stats.siteKeys++;
                    continue;
                }
                if (done.contains(row.key())) {
                    stats.alreadyShifted++;
                    continue;
                }
                if (isPlaceholder(row, placeholders.uuid(), parentKeys)) {
                    stats.placeholders++;
                    continue;
                }
                String edit = tz.convertEdited() ? null : editedAfter(row, tz.before());
                if (edit != null) {
                    stats.edited++;
                    reports.edited().row(List.of(name, "" + row.key(), nz(row.uuid()), edit,
                            TimeShift.format(row.values().get(edit)), describe(row, datetimes)));
                    continue;
                }
                List<Value> values = new ArrayList<>();
                for (String col : converted) {
                    ColumnStats cs = stats.columns.get(col);
                    if (row.unreadable().contains(col)) {
                        cs.unreadable++;
                        continue;
                    }
                    LocalDateTime value = row.values().get(col);
                    if (value == null) {
                        continue;
                    }
                    if (!value.isBefore(tz.before())) {
                        cs.postMigration++;
                        continue;
                    }
                    if (value.toLocalTime().equals(LocalTime.MIDNIGHT)) {
                        cs.midnight++;
                    }
                    TimeShift.Conversion conv = shift.convert(value);
                    if (conv.kind() == TimeShift.Kind.GAP) {
                        cs.gap++;
                        reports.gap().row(List.of(name, "" + row.key(), nz(row.uuid()), col, TimeShift.format(value)));
                        continue;
                    }
                    if (conv.kind() == TimeShift.Kind.AMBIGUOUS) {
                        cs.ambiguous++;
                        reports.ambiguous().row(List.of(name, "" + row.key(), nz(row.uuid()), col,
                                TimeShift.format(value), TimeShift.format(conv.value())));
                    }
                    cs.changed++;
                    cs.before[value.getHour()]++;
                    cs.after[conv.value().getHour()]++;
                    values.add(new Value(col, value, conv.value()));
                }
                stats.endBeforeStart += checkOrder(name, row, values, reports);
                if (values.isEmpty()) {
                    continue;
                }
                todo.add(new RowChange(row, values));
                stats.rowsToChange++;
                stats.valuesToChange += values.size();
                for (Value v : values) {
                    reports.changes().row(List.of(name, "" + row.key(), nz(row.uuid()), v.column(),
                            TimeShift.format(v.oldValue()), TimeShift.format(v.newValue())));
                }
            }
            if (apply && !todo.isEmpty()) {
                write(conn, name, pk, todo, runId);
            }
            Log.info(String.format("  %s: %d rows read (up to %s %d), %d %s", name, stats.rowsInRange, pk, last,
                    stats.rowsToChange, apply ? "converted and committed" : "to convert"));
        }
        listNullAnchors(conn, table, pk, c.anchor(), stats, reports);
    }

    /** Lists the rows that have no value in the anchor column: they cannot be placed in the range, so stay as they are. */
    private static void listNullAnchors(Connection conn, Schema.Table table, String pk, String anchor,
                                        TableStats stats, Reports reports) throws SQLException, IOException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT " + Db.q(pk)
                + (table.hasUuid() ? ", " + Db.q("uuid") : "") + " FROM " + Db.q(table.name()) + " WHERE "
                + Db.q(anchor) + " IS NULL ORDER BY " + Db.q(pk)); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                stats.nullAnchor++;
                reports.nullAnchor().row(List.of(table.name(), "" + rs.getLong(1),
                        table.hasUuid() ? nz(rs.getString(2)) : "", anchor));
            }
        }
    }

    /** The first edit column of {@code row} at or after {@code before}, or null when it was not edited since. */
    private static String editedAfter(Row row, LocalDateTime before) {
        for (String col : row.values().keySet().stream().sorted().toList()) {
            LocalDateTime value = row.values().get(col);
            if (value != null && !value.isBefore(before) && EDIT_COLUMNS.contains(col.toLowerCase(Locale.ROOT))) {
                return col;
            }
        }
        return null;
    }

    private static String describe(Row row, List<String> columns) {
        return columns.stream().map(c -> c + "=" + (row.values().get(c) == null ? "" : TimeShift.format(row.values()
                .get(c)))).collect(Collectors.joining("; "));
    }

    private static List<Row> read(Connection conn, String sql, Schema.Table table, long after, Options.Timezone tz,
                                  List<String> datetimes, Set<String> refColumns) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setLong(i++, after);
            ps.setObject(i++, tz.before());
            if (tz.from() != null) {
                ps.setObject(i++, tz.from());
            }
            ps.setInt(i, tz.chunkSize());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long key = rs.getLong(table.primaryKey().get(0));
                    String uuid = table.hasUuid() ? rs.getString("uuid") : null;
                    Map<String, LocalDateTime> values = new HashMap<>();
                    Set<String> unreadable = new HashSet<>();
                    for (String col : datetimes) {
                        try {
                            values.put(col, rs.getObject(col, LocalDateTime.class));
                        } catch (SQLException | RuntimeException e) {
                            unreadable.add(col); // a zero date, say: left alone
                        }
                    }
                    Map<String, Long> refs = new HashMap<>();
                    for (String col : refColumns) {
                        long ref = rs.getLong(col);
                        if (!rs.wasNull()) {
                            refs.put(col, ref);
                        }
                    }
                    rows.add(new Row(key, uuid, values, unreadable, refs));
                }
            }
        }
        return rows;
    }

    private static boolean isPlaceholder(Row row, String placeholderUuid, Map<String, Set<Long>> parentKeys) {
        if (placeholderUuid != null && placeholderUuid.equalsIgnoreCase(nz(row.uuid()).trim())) {
            return true;
        }
        for (LocalDateTime value : row.values().values()) {
            if (value != null && value.toLocalDate().equals(TimezoneTables.EPOCH_DAY.toLocalDate())) {
                return true;
            }
        }
        for (Map.Entry<String, Set<Long>> e : parentKeys.entrySet()) {
            Long ref = row.refs().get(e.getKey());
            if (ref != null && e.getValue().contains(ref)) {
                return true;
            }
        }
        return false;
    }

    /** Lists start/end pairs that end up in the wrong order; returns how many the conversion put out of order. */
    private static int checkOrder(String table, Row row, List<Value> values, Reports reports) throws IOException {
        Map<String, LocalDateTime> after = new HashMap<>(row.values());
        values.forEach(v -> after.put(v.column(), v.newValue()));
        int inverted = 0;
        for (List<String> pair : END_PAIRS) {
            String start = matching(row.values().keySet(), pair.get(0));
            String end = matching(row.values().keySet(), pair.get(1));
            if (start == null || end == null) {
                continue;
            }
            LocalDateTime newStart = after.get(start);
            LocalDateTime newEnd = after.get(end);
            if (newStart == null || newEnd == null || !newEnd.isBefore(newStart)) {
                continue;
            }
            LocalDateTime oldStart = row.values().get(start);
            LocalDateTime oldEnd = row.values().get(end);
            boolean already = oldEnd.isBefore(oldStart);
            if (!already) {
                inverted++;
            }
            reports.endBeforeStart().row(List.of(table, "" + row.key(), nz(row.uuid()), start, end,
                    TimeShift.format(oldStart), TimeShift.format(oldEnd), TimeShift.format(newStart),
                    TimeShift.format(newEnd), already ? "already before the start" : "put before the start"));
        }
        return inverted;
    }

    private static String matching(Set<String> columns, String name) {
        return columns.stream().filter(c -> c.equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    /** Converts one chunk and logs it, in one transaction. */
    private static void write(Connection conn, String table, String pk, List<RowChange> todo, String runId)
            throws SQLException {
        conn.setAutoCommit(false);
        Map<String, PreparedStatement> updates = new LinkedHashMap<>();
        Map<PreparedStatement, List<RowChange>> batched = new LinkedHashMap<>();
        try (PreparedStatement ledger = conn.prepareStatement("INSERT INTO " + ShiftLog.TABLE
                + " (table_name, row_key, patient_uuid, changes, shifted_at, run_id) VALUES (?, ?, ?, ?, ?, ?)")) {
            String now = TimeShift.format(LocalDateTime.now().withNano(0));
            for (RowChange rc : todo) {
                String sql = "UPDATE " + Db.q(table) + " SET "
                        + rc.values().stream().map(v -> Db.q(v.column()) + " = ?").collect(Collectors.joining(", "))
                        + " WHERE " + Db.q(pk) + " = ?"
                        + rc.values().stream().map(v -> " AND " + Db.q(v.column()) + " = ?")
                        .collect(Collectors.joining());
                PreparedStatement ps = updates.get(sql);
                if (ps == null) {
                    ps = conn.prepareStatement(sql);
                    updates.put(sql, ps);
                }
                int i = 1;
                for (Value v : rc.values()) {
                    ps.setObject(i++, v.newValue());
                }
                ps.setLong(i++, rc.row().key());
                for (Value v : rc.values()) {
                    ps.setObject(i++, v.oldValue());
                }
                ps.addBatch();
                batched.computeIfAbsent(ps, k -> new ArrayList<>()).add(rc);

                ledger.setString(1, table);
                ledger.setString(2, Long.toString(rc.row().key()));
                ledger.setString(3, "");
                ledger.setString(4, TimezoneUndo.formatChanges(rc.values().stream()
                        .map(v -> new TimeShift.Change(v.column(), TimeShift.format(v.oldValue()),
                                TimeShift.format(v.newValue()))).toList()));
                ledger.setString(5, now);
                ledger.setString(6, runId);
                ledger.addBatch();
            }
            for (Map.Entry<PreparedStatement, List<RowChange>> e : batched.entrySet()) {
                int[] counts = e.getKey().executeBatch();
                for (int i = 0; i < counts.length; i++) {
                    if (counts[i] != 1 && counts[i] != Statement.SUCCESS_NO_INFO) {
                        throw new SQLException(table + " row " + e.getValue().get(i).row().key()
                                + " changed since it was read; this chunk was rolled back");
                    }
                }
            }
            ledger.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            for (PreparedStatement ps : updates.values()) {
                ps.close();
            }
            conn.setAutoCommit(true);
        }
    }

    /** Keys among {@code rows} that the ledger says were shifted already. */
    private static Set<Long> alreadyShifted(Connection conn, String table, List<Row> rows) throws SQLException {
        Set<Long> done = new HashSet<>();
        List<String> keys = rows.stream().map(r -> Long.toString(r.key())).toList();
        for (List<String> batch : Db.batches(keys)) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT row_key FROM " + ShiftLog.TABLE
                    + " WHERE table_name = ? AND row_key IN (" + Db.placeholders(batch.size()) + ")")) {
                ps.setString(1, table);
                for (int i = 0; i < batch.size(); i++) {
                    ps.setString(i + 2, batch.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            done.add(Long.parseLong(rs.getString(1)));
                        } catch (NumberFormatException e) {
                            // not a key of this table
                        }
                    }
                }
            }
        }
        return done;
    }

    private static void report(Reports reports, String table, String status, String detail, TableStats s)
            throws IOException {
        reports.tables().row(List.of(table, status, "" + s.rowsInRange, "" + s.rowsToChange, "" + s.valuesToChange,
                "" + s.siteKeys, "" + s.placeholders, "" + s.alreadyShifted, "" + s.endBeforeStart, "" + s.edited,
                "" + s.nullAnchor, nz(detail)));
        for (Map.Entry<String, ColumnStats> e : s.columns.entrySet()) {
            ColumnStats c = e.getValue();
            reports.columns().row(List.of(table, e.getKey(), "" + c.changed, "" + c.postMigration, "" + c.midnight,
                    "" + c.ambiguous, "" + c.gap, "" + c.unreadable));
            if (c.changed > 0) {
                for (int h = 0; h < 24; h++) {
                    reports.histogram().row(List.of(table, e.getKey(), "" + h, "" + c.before[h], "" + c.after[h]));
                }
            }
        }
    }

    private static boolean confirm(List<Candidate> candidates, SyncConfig.DbSettings settings) throws IOException {
        System.out.print("About to convert the datetimes of " + candidates.stream().filter(Candidate::usable)
                .map(c -> c.table().name()).collect(Collectors.joining(", ")) + " in " + settings.label() + " ("
                + settings.url() + "), committing chunk by chunk. Make sure you have a backup. Type APPLY to"
                + " continue: ");
        System.out.flush();
        String answer = new BufferedReader(new InputStreamReader(System.in)).readLine();
        return "APPLY".equals(answer == null ? null : answer.trim());
    }

    private static String nz(Object value) {
        return value == null ? "" : value.toString();
    }
}
