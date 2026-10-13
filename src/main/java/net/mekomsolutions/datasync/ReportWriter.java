package net.mekomsolutions.datasync;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.mekomsolutions.datasync.MissingRowFinder.MissingRow;
import net.mekomsolutions.datasync.MissingRowFinder.TableDiff;
import net.mekomsolutions.datasync.SyncPlanner.BlockedRow;
import net.mekomsolutions.datasync.SyncPlanner.Plan;

/**
 * Writes the report:
 * <ul>
 *   <li>summary.csv: per table, rows in the source, rows missing from the target, how many can be synced</li>
 *   <li>missing/&lt;table&gt;.csv: every missing row as it is in the source database</li>
 *   <li>blocked.csv: missing rows that cannot be synced, with the reason</li>
 * </ul>
 */
final class ReportWriter {

    private ReportWriter() {}

    static void write(Path dir, List<TableDiff> diffs, Plan plan) throws IOException {
        Files.createDirectories(dir.resolve("missing"));

        Map<String, Integer> blockedPerTable = new HashMap<>();
        for (BlockedRow b : plan.blocked()) {
            blockedPerTable.merge(b.table().name(), 1, Integer::sum);
        }

        try (Writer w = Files.newBufferedWriter(dir.resolve("summary.csv"), StandardCharsets.UTF_8)) {
            line(w, List.of("table", "source_rows", "missing_in_target", "syncable", "blocked"));
            for (TableDiff d : diffs) {
                int syncable = plan.rows().getOrDefault(d.table(), List.of()).size();
                line(w, List.of(d.table().name(), String.valueOf(d.sourceRows()), String.valueOf(d.missing().size()),
                        String.valueOf(syncable), String.valueOf(blockedPerTable.getOrDefault(d.table().name(), 0))));
            }
        }

        for (TableDiff d : diffs) {
            if (d.missing().isEmpty()) {
                continue;
            }
            List<String> header = new ArrayList<>();
            d.table().columns().forEach(c -> header.add(c.name()));
            try (Writer w = Files.newBufferedWriter(dir.resolve("missing").resolve(d.table().name() + ".csv"),
                    StandardCharsets.UTF_8)) {
                line(w, header);
                for (MissingRow r : d.missing()) {
                    List<String> cells = new ArrayList<>();
                    for (String col : header) {
                        cells.add(Db.key(r.values().get(col)));
                    }
                    line(w, cells);
                }
            }
        }

        try (Writer w = Files.newBufferedWriter(dir.resolve("blocked.csv"), StandardCharsets.UTF_8)) {
            line(w, List.of("table", "row", "reason"));
            for (BlockedRow b : plan.blocked()) {
                Object uuid = b.row().values().get("uuid");
                line(w, List.of(b.table().name(), uuid != null ? uuid.toString()
                        : b.row().identity().replace('\u001F', '/'), b.reason()));
            }
        }
    }

    private static void line(Writer w, List<String> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                w.write(',');
            }
            w.write(csv(cells.get(i)));
        }
        w.write('\n');
    }

    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
