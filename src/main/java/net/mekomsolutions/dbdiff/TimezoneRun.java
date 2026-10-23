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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import net.mekomsolutions.dbdiff.TimeShift.Change;
import net.mekomsolutions.dbdiff.TimeShift.Window;

/**
 * The timezone command, on one database: for every selected patient, finds the datetimes stored in the wrong time
 * zone (see {@link TimeShift}) and lists them, or with --apply shifts them, one transaction per patient. Each
 * shifted row is recorded in {@value #LEDGER} in the same transaction, with its old values, so no row is ever
 * shifted twice (a row two patients share, or a second run) and every shift can be undone.
 */
final class TimezoneRun {

    static final String LEDGER = "dbdiff_timezone_shift";

    enum Status {
        /** Dry run: the patient has values to shift; see timezone-changes.csv. */
        WOULD_SHIFT,
        /** The values were shifted and committed. */
        SHIFTED,
        /** No value of the patient's falls in the window, or all of them were shifted already. */
        NOTHING_TO_SHIFT,
        /** No window applies to the patient; the detail says why. */
        SKIPPED,
        NOT_FOUND,
        /** Something went wrong; nothing was written for this patient. */
        FAILED
    }

    record Outcome(String patientUuid, Status status, String location, String window, int rows, int rowsToShift,
                   int valuesToShift, int alreadyShifted, int rowsWithoutKey, String detail) {

        static Outcome of(String patientUuid, Status status, String detail) {
            return new Outcome(patientUuid, status, null, null, 0, 0, 0, 0, 0, detail);
        }
    }

    /** The values to shift in one row. */
    private record RowShift(Schema.Table table, SourceRow row, String key, List<Change> changes) {}

    private final Options options;
    private final SyncConfig config;
    private final SyncRun.SchemaReader schemaReader;

    TimezoneRun(Options options, SyncConfig config) {
        this(options, config, Schema::read);
    }

    TimezoneRun(Options options, SyncConfig config, SyncRun.SchemaReader schemaReader) {
        this.options = options;
        this.config = config;
        this.schemaReader = schemaReader;
    }

    /** Runs and returns the process exit code: 0 when no patient failed, 1 otherwise. */
    int execute() throws SQLException, IOException {
        boolean apply = options.mode() == RunMode.TIMEZONE_APPLY;
        Options.Timezone tz = options.timezone();
        SyncConfig.DbSettings settings = tz.database().equals("source") ? config.source() : config.target();
        Log.info("Database to fix: " + settings.label() + " " + settings.url());
        try (Connection conn = settings.connect()) {
            conn.setReadOnly(!apply);
            Schema schema = schemaReader.read(conn);
            TimeShift shift = new TimeShift(tz.zone(), tz.skipped(), tz.toUtc());
            TimezoneCutoffs cutoffs = TimezoneCutoffs.load(tz.cutoffsFile(),
                    tz.before() == null ? null : new Window(tz.from(), tz.before()), conn);
            Log.info("Rows created in these windows move " + (tz.toUtc() ? "from " + tz.zone() + " time to UTC"
                    : "from UTC to " + tz.zone() + " time") + "; never shifted: " + String.join(", ", tz.skipped()));
            cutoffs.describeAll().forEach((location, window) -> Log.info("  " + location + ": " + window));

            IdentityResolver resolver = new IdentityResolver(schema, conn, conn);
            PatientRecordCollector collector = new PatientRecordCollector(schema,
                    new TableLinks(schema, new SyncScope(schema, config)), new SourceReader(conn, resolver));

            List<String> patients = PatientList.resolve(options, conn);
            Log.info((apply ? "Shifting, committing patient by patient," : "Dry run (nothing is written)")
                    + " for " + patients.size() + " patient(s)");
            if (patients.isEmpty()) {
                return 0;
            }
            if (apply && !options.assumeYes() && !confirm(patients.size(), settings)) {
                Log.info("Cancelled");
                return 1;
            }
            if (apply) {
                createLedger(conn);
            }
            boolean ledger = apply || ledgerExists(conn);

            Map<Status, Integer> tally = new EnumMap<>(Status.class);
            Path dir = config.outputDir().resolve(
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-timezone");
            Files.createDirectories(dir);
            try (CsvWriter patientsCsv = new CsvWriter(dir.resolve("timezone-patients.csv"), List.of("patient_uuid",
                    "status", "location", "window", "rows", "rows_to_shift", "values_to_shift", "already_shifted",
                    "rows_without_key", "detail"));
                 CsvWriter changesCsv = new CsvWriter(dir.resolve("timezone-changes.csv"), List.of("patient_uuid",
                         "table", "row_key", "uuid", "column", "old_value", "new_value"))) {
                for (int i = 0; i < patients.size(); i++) {
                    String uuid = patients.get(i);
                    Outcome outcome;
                    try {
                        outcome = shiftOne(conn, collector, resolver, cutoffs, shift, ledger, apply, uuid, changesCsv);
                    } catch (Exception e) {
                        outcome = Outcome.of(uuid, Status.FAILED, e.getMessage());
                    }
                    tally.merge(outcome.status(), 1, Integer::sum);
                    patientsCsv.row(List.of(uuid, outcome.status().name(), nz(outcome.location()),
                            nz(outcome.window()), "" + outcome.rows(), "" + outcome.rowsToShift(),
                            "" + outcome.valuesToShift(), "" + outcome.alreadyShifted(), "" + outcome.rowsWithoutKey(),
                            nz(outcome.detail())));
                    logProgress(i + 1, patients.size(), outcome);
                    if (outcome.status() == Status.FAILED && !conn.isValid(5)) {
                        throw new SQLException("Lost the database connection after " + (i + 1) + " of "
                                + patients.size() + " patients; stopping. The report covers the patients processed.");
                    }
                }
            }
            Log.info("Patients: " + tally.entrySet().stream().map(e -> e.getKey() + " " + e.getValue())
                    .collect(Collectors.joining(", ")));
            Log.info("Report written to " + dir.toAbsolutePath());
            if (!apply) {
                Log.info("Nothing was written. Check timezone-changes.csv, then re-run with --apply.");
            }
            return tally.containsKey(Status.FAILED) ? 1 : 0;
        }
    }

    private static Outcome shiftOne(Connection conn, PatientRecordCollector collector, IdentityResolver resolver,
                                    TimezoneCutoffs cutoffs, TimeShift shift, boolean ledger, boolean apply,
                                    String uuid, CsvWriter changesCsv) throws SQLException, IOException {
        Optional<PatientRecord> found = collector.collect(uuid);
        if (found.isEmpty()) {
            return Outcome.of(uuid, Status.NOT_FOUND, "no such patient");
        }
        PatientRecord record = found.get();
        try {
            TimezoneCutoffs.PatientWindow site = cutoffs.windowFor(record);
            if (site.window() == null) {
                return new Outcome(uuid, Status.SKIPPED, site.location(), null, record.size(), 0, 0, 0, 0,
                        site.problem());
            }
            List<RowShift> shifts = new ArrayList<>();
            int withoutKey = 0;
            for (Map.Entry<Schema.Table, List<SourceRow>> e : record.rows().entrySet()) {
                Schema.Table table = e.getKey();
                for (SourceRow row : e.getValue()) {
                    List<Change> changes = shift.changes(table, row.values(), site.window());
                    if (changes.isEmpty()) {
                        continue;
                    }
                    if (table.primaryKey().isEmpty()) {
                        withoutKey++;
                        continue;
                    }
                    shifts.add(new RowShift(table, row, rowKey(table, row), changes));
                }
            }
            Set<String> done = ledger ? alreadyShifted(conn, shifts) : Set.of();
            List<RowShift> todo = shifts.stream().filter(s -> !done.contains(s.table().name() + "|" + s.key()))
                    .toList();
            int values = todo.stream().mapToInt(s -> s.changes().size()).sum();
            Status status = todo.isEmpty() ? Status.NOTHING_TO_SHIFT : apply ? Status.SHIFTED : Status.WOULD_SHIFT;
            if (status == Status.SHIFTED) {
                write(conn, uuid, todo);
            }
            for (RowShift s : todo) {
                for (Change c : s.changes()) {
                    changesCsv.row(List.of(uuid, s.table().name(), s.key(), nz(s.row().values().get("uuid")),
                            c.column(), c.oldValue(), c.newValue()));
                }
            }
            return new Outcome(uuid, status, site.location(), site.window().toString(), record.size(), todo.size(),
                    values, shifts.size() - todo.size(), withoutKey, null);
        } finally {
            resolver.forget(record.tables());
        }
    }

    /** Updates the rows and records them in the ledger, in one transaction. */
    private static void write(Connection conn, String patientUuid, List<RowShift> shifts) throws SQLException {
        conn.setAutoCommit(false);
        Map<String, PreparedStatement> updates = new LinkedHashMap<>();
        Map<PreparedStatement, List<RowShift>> batched = new LinkedHashMap<>();
        try (PreparedStatement ledger = conn.prepareStatement("INSERT INTO " + LEDGER
                + " (table_name, row_key, patient_uuid, changes, shifted_at) VALUES (?, ?, ?, ?, ?)")) {
            String now = TimeShift.format(LocalDateTime.now().withNano(0));
            for (RowShift s : shifts) {
                String sql = updateSql(s);
                PreparedStatement ps = updates.get(sql);
                if (ps == null) {
                    ps = conn.prepareStatement(sql);
                    updates.put(sql, ps);
                }
                int i = 1;
                for (Change c : s.changes()) {
                    ps.setString(i++, c.newValue());
                }
                for (String pk : s.table().primaryKey()) {
                    ps.setObject(i++, s.row().values().get(pk));
                }
                for (Change c : s.changes()) {
                    ps.setString(i++, c.oldValue());
                }
                ps.addBatch();
                batched.computeIfAbsent(ps, k -> new ArrayList<>()).add(s);

                ledger.setString(1, s.table().name());
                ledger.setString(2, s.key());
                ledger.setString(3, patientUuid);
                ledger.setString(4, s.changes().stream().map(c -> c.column() + ": " + c.oldValue() + " -> "
                        + c.newValue()).collect(Collectors.joining("; ")));
                ledger.setString(5, now);
                ledger.addBatch();
            }
            for (Map.Entry<PreparedStatement, List<RowShift>> e : batched.entrySet()) {
                int[] counts = e.getKey().executeBatch();
                for (int i = 0; i < counts.length; i++) {
                    if (counts[i] != 1 && counts[i] != Statement.SUCCESS_NO_INFO) {
                        RowShift s = e.getValue().get(i);
                        throw new SQLException(s.table().name() + " row " + s.key()
                                + " changed since it was read; nothing was written for this patient");
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

    /** Sets the new values, matching the row by primary key and by its old values, so a changed row is not hit. */
    private static String updateSql(RowShift s) {
        return "UPDATE " + Db.q(s.table().name()) + " SET "
                + s.changes().stream().map(c -> Db.q(c.column()) + " = ?").collect(Collectors.joining(", "))
                + " WHERE "
                + s.table().primaryKey().stream().map(pk -> Db.q(pk) + " = ?").collect(Collectors.joining(" AND "))
                + s.changes().stream().map(c -> " AND " + Db.q(c.column()) + " = ?").collect(Collectors.joining());
    }

    private static String rowKey(Schema.Table table, SourceRow row) {
        return table.primaryKey().stream().map(pk -> Db.key(row.values().get(pk))).collect(Collectors.joining("/"));
    }

    /** "table|key" of the rows among {@code shifts} the ledger says were shifted already. */
    private static Set<String> alreadyShifted(Connection conn, List<RowShift> shifts) throws SQLException {
        Map<String, List<String>> keysByTable = new LinkedHashMap<>();
        shifts.forEach(s -> keysByTable.computeIfAbsent(s.table().name(), k -> new ArrayList<>()).add(s.key()));
        Set<String> done = new HashSet<>();
        for (Map.Entry<String, List<String>> e : keysByTable.entrySet()) {
            for (List<String> batch : Db.batches(e.getValue())) {
                String sql = "SELECT row_key FROM " + LEDGER + " WHERE table_name = ? AND row_key IN ("
                        + Db.placeholders(batch.size()) + ")";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, e.getKey());
                    for (int i = 0; i < batch.size(); i++) {
                        ps.setString(i + 2, batch.get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            done.add(e.getKey() + "|" + rs.getString(1));
                        }
                    }
                }
            }
        }
        return done;
    }

    static void createLedger(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS " + LEDGER + " ("
                    + "table_name VARCHAR(64) NOT NULL, "
                    + "row_key VARCHAR(255) NOT NULL, "
                    + "patient_uuid CHAR(38) NOT NULL, "
                    + "changes TEXT NOT NULL, "
                    + "shifted_at DATETIME NOT NULL, "
                    + "PRIMARY KEY (table_name, row_key))");
        }
    }

    private static boolean ledgerExists(Connection conn) {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT 1 FROM " + LEDGER + " WHERE 1 = 0")) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static boolean confirm(int patients, SyncConfig.DbSettings settings) throws IOException {
        System.out.print("About to shift the historical datetimes of " + patients + " patient(s) in "
                + settings.label() + " (" + settings.url() + "). Each patient is committed on its own. Make sure you"
                + " have a backup. Type APPLY to continue: ");
        System.out.flush();
        String answer = new BufferedReader(new InputStreamReader(System.in)).readLine();
        return "APPLY".equals(answer == null ? null : answer.trim());
    }

    private static void logProgress(int index, int total, Outcome o) {
        String position = String.format("[%" + String.valueOf(total).length() + "d/%d]", index, total);
        String detail = o.detail() != null ? o.detail()
                : String.format("%s, rows %d, values %d, already shifted %d", o.location(), o.rowsToShift(),
                        o.valuesToShift(), o.alreadyShifted());
        String line = String.format("%s %s  %-16s %s", position, o.patientUuid(), o.status(), detail);
        if (o.status() == Status.FAILED) {
            Log.warn(line);
        } else {
            Log.info(line);
        }
    }

    private static String nz(Object value) {
        return value == null ? "" : value.toString();
    }
}
