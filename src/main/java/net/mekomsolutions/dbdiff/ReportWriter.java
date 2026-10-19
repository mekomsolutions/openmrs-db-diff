package net.mekomsolutions.dbdiff;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes a run's report into one folder, a patient at a time, so memory does not grow with the number of patients:
 *
 * <ul>
 *   <li>patients.csv: one line per patient with its status and counts</li>
 *   <li>missing/&lt;table&gt;.csv: every missing row as it is in the source, with the patient it was found for
 *       and whether it can be synced</li>
 *   <li>blocked.csv: missing rows that cannot be synced, with the reason</li>
 *   <li>summary.csv: per table totals, written when the report is closed</li>
 * </ul>
 */
final class ReportWriter implements AutoCloseable {

    private final Path dir;
    private final CsvWriter patients;
    private final CsvWriter blocked;
    private final Map<String, CsvWriter> missingByTable = new HashMap<>();
    private final Map<String, TableTotals> totals = new TreeMap<>();

    ReportWriter(Path dir) throws IOException {
        this.dir = Files.createDirectories(dir);
        Files.createDirectories(dir.resolve("missing"));
        this.patients = new CsvWriter(dir.resolve("patients.csv"), List.of("patient_uuid", "status", "source_rows",
                "missing_in_target", "syncable", "blocked", "inserted", "error"));
        this.blocked = new CsvWriter(dir.resolve("blocked.csv"), List.of("patient_uuid", "table", "row", "reason"));
    }

    Path dir() {
        return dir;
    }

    void add(PatientResult result) throws IOException {
        String patient = result.patientUuid();
        patients.row(List.of(patient, result.status().name(), String.valueOf(result.sourceRows()),
                String.valueOf(result.missing()), String.valueOf(result.syncable()), String.valueOf(result.blocked()),
                String.valueOf(result.insertedTotal()), result.error() == null ? "" : result.error()));

        if (result.record() != null) {
            result.record().rows().forEach((table, rows) -> totals(table.name()).sourceRows += rows.size());
        }
        if (result.plan() != null) {
            for (List<SyncPlan.PlannedRow> rows : result.plan().rows().values()) {
                for (SyncPlan.PlannedRow row : rows) {
                    writeMissing(patient, "syncable", row.table(), row.row());
                    totals(row.table().name()).syncable++;
                }
            }
            for (SyncPlan.BlockedRow row : result.plan().blocked()) {
                writeMissing(patient, "blocked", row.table(), row.row());
                blocked.row(List.of(patient, row.table().name(), row.row().describe(), row.reason()));
                totals(row.table().name()).blocked++;
            }
        }
        result.inserted().forEach((table, n) -> totals(table).inserted += n);
    }

    private void writeMissing(String patient, String status, Schema.Table table, SourceRow row) throws IOException {
        CsvWriter out = missingByTable.get(table.name());
        if (out == null) {
            List<String> header = new ArrayList<>(List.of("patient_uuid", "sync_status"));
            table.columns().forEach(c -> header.add(c.name()));
            out = new CsvWriter(dir.resolve("missing").resolve(table.name() + ".csv"), header);
            missingByTable.put(table.name(), out);
        }
        List<String> cells = new ArrayList<>(List.of(patient, status));
        table.columns().forEach(c -> cells.add(Db.key(row.values().get(c.name()))));
        out.row(cells);
    }

    @Override
    public void close() throws IOException {
        try (CsvWriter summary = new CsvWriter(dir.resolve("summary.csv"),
                List.of("table", "source_rows", "missing_in_target", "syncable", "blocked", "inserted"))) {
            for (Map.Entry<String, TableTotals> e : totals.entrySet()) {
                TableTotals t = e.getValue();
                summary.row(List.of(e.getKey(), String.valueOf(t.sourceRows), String.valueOf(t.syncable + t.blocked),
                        String.valueOf(t.syncable), String.valueOf(t.blocked), String.valueOf(t.inserted)));
            }
        } finally {
            patients.close();
            blocked.close();
            for (CsvWriter out : missingByTable.values()) {
                out.close();
            }
        }
    }

    private TableTotals totals(String table) {
        return totals.computeIfAbsent(table, t -> new TableTotals());
    }

    private static final class TableTotals {
        long sourceRows;
        long syncable;
        long blocked;
        long inserted;
    }
}
