package net.mekomsolutions.dbdiff;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import net.mekomsolutions.dbdiff.PatientResult.Status;

/**
 * One run of the tool: every selected patient goes through the same find, plan and sync steps, one at a time. A
 * patient that fails is reported and the run moves on to the next one.
 */
final class SyncRun {

    /** Reads a database's schema; {@link Schema#read} except in tests. */
    @FunctionalInterface
    interface SchemaReader {
        Schema read(Connection connection) throws SQLException;
    }

    private final Options options;
    private final SyncConfig config;
    private final SchemaReader schemaReader;

    SyncRun(Options options, SyncConfig config) {
        this(options, config, Schema::read);
    }

    SyncRun(Options options, SyncConfig config, SchemaReader schemaReader) {
        this.options = options;
        this.config = config;
        this.schemaReader = schemaReader;
    }

    /** Runs and returns the process exit code: 0 when every patient went through, 1 otherwise. */
    int execute() throws SQLException, IOException {
        try (Databases db = Databases.open(config)) {
            Log.info("Source (has the extra data): " + config.source().label() + " " + config.source().url());
            Log.info("Target (source of truth, receives the data): " + config.target().label() + " "
                    + config.target().url());
            Schema source = schemaReader.read(db.source());
            Schema target = schemaReader.read(db.targetRead());
            warnAboutTablesMissingFromTarget(source, target);
            if (options.mode() == RunMode.METADATA) {
                return checkMetadata(db, source, target);
            }
            MetadataDecisions decisions = MetadataDecisions.load(config.metadataDecisionsFile());
            if (!decisions.isEmpty()) {
                Log.info("Using the metadata decisions in " + config.metadataDecisionsFile());
            }
            PatientSyncer syncer = PatientSyncer.create(db, source, target, config, decisions, options.mode());

            List<String> patients = PatientList.resolve(options, db.source());
            Log.info(describeMode() + " for " + patients.size() + " patient(s)");
            if (patients.isEmpty()) {
                return 0;
            }
            if (options.mode() == RunMode.APPLY && !options.assumeYes() && !confirm(patients.size())) {
                Log.info("Cancelled");
                return 1;
            }

            Map<Status, Integer> tally = new EnumMap<>(Status.class);
            long blockedRows = 0;
            Path reportDir;
            try (ReportWriter report = new ReportWriter(newReportDir())) {
                reportDir = report.dir();
                for (int i = 0; i < patients.size(); i++) {
                    PatientResult result = syncOne(syncer, patients.get(i));
                    report.add(result);
                    tally.merge(result.status(), 1, Integer::sum);
                    blockedRows += result.blocked();
                    logProgress(i + 1, patients.size(), result);
                    if (result.status() == Status.FAILED && !db.allValid()) {
                        throw new SQLException("Lost a database connection after " + (i + 1) + " of "
                                + patients.size() + " patients; stopping. The report covers the patients processed.");
                    }
                }
            }
            logSummary(tally, reportDir);
            if (blockedRows > 0) {
                Log.info(blockedRows + " missing row(s) are blocked; see blocked.csv. When they point at metadata the"
                        + " target lacks, run the metadata command to reconcile it first.");
            }
            return tally.containsKey(Status.FAILED) ? 1 : 0;
        }
    }

    /** Compares metadata, writes metadata.csv and the suggested decisions, and says what to do next. */
    private int checkMetadata(Databases db, Schema source, Schema target) throws SQLException, IOException {
        Log.info("Comparing metadata");
        List<MetadataCheck.Finding> findings =
                new MetadataCheck(db.source(), db.targetRead(), source, target, config).run();
        Path dir = newReportDir();
        MetadataCheck.write(dir, findings);
        MetadataCheck.totals(findings).forEach((table, t) ->
                Log.info(String.format("%-32s missing from the target %6d, used by patient data %6d", table, t[0], t[1])));
        long needed = findings.stream().filter(MetadataCheck.Finding::needed).count();
        Path decisionsFile = config.metadataDecisionsFile() != null ? config.metadataDecisionsFile()
                : Path.of("config", "metadata-decisions.csv");
        Log.info("Report written to " + dir.toAbsolutePath());
        if (needed == 0) {
            Log.info("The target has all the metadata patient data uses.");
        } else {
            Log.info(needed + " metadata row(s) used by patient data are missing from the target. Review "
                    + dir.resolve("metadata-decisions.csv") + " (MAP to an existing target row, COPY, or SKIP), then"
                    + " save it as " + decisionsFile + " and run a dry run.");
        }
        return 0;
    }

    private void warnAboutTablesMissingFromTarget(Schema source, Schema target) {
        source.tables().stream()
                .filter(t -> !config.isExcluded(t.name()) && target.table(t.name()) == null)
                .forEach(t -> Log.warn("Table " + t.name() + " is not in the target database, skipping it"));
    }

    /** Processes one patient; any failure is turned into a FAILED result so the run can carry on. */
    private static PatientResult syncOne(PatientSyncer syncer, String patientUuid) {
        try {
            return syncer.sync(patientUuid);
        } catch (Exception e) {
            return PatientResult.failed(patientUuid, e);
        }
    }

    private String describeMode() {
        return switch (options.mode()) {
            case REPORT -> "Report";
            case DRY_RUN -> "Dry run (every insert is rolled back)";
            case APPLY -> "Sync, committing patient by patient";
            case METADATA -> "Metadata check";
            case TIMEZONE, TIMEZONE_APPLY -> "Timezone shift";
        };
    }

    private boolean confirm(int patients) throws IOException {
        System.out.print("About to sync " + patients + " patient(s) into " + config.target().label() + " ("
                + config.target().url() + "). Each patient is committed on its own. Make sure you have a backup. "
                + "Type APPLY to continue: ");
        System.out.flush();
        String answer = new BufferedReader(new InputStreamReader(System.in)).readLine();
        return "APPLY".equals(answer == null ? null : answer.trim());
    }

    private Path newReportDir() {
        return config.outputDir().resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
    }

    private static void logProgress(int index, int total, PatientResult r) {
        String position = String.format("[%" + String.valueOf(total).length() + "d/%d]", index, total);
        String detail = switch (r.status()) {
            case NOT_FOUND -> "no such patient in the source";
            case FAILED -> r.error();
            default -> String.format("missing %d, syncable %d, blocked %d, inserted %d",
                    r.missing(), r.syncable(), r.blocked(), r.insertedTotal());
        };
        String line = String.format("%s %s  %-10s %s", position, r.patientUuid(), r.status(), detail);
        if (r.status() == Status.FAILED) {
            Log.warn(line);
        } else {
            Log.info(line);
        }
    }

    private void logSummary(Map<Status, Integer> tally, Path reportDir) {
        Log.info("Patients: " + tally.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining(", ")));
        Log.info("Report written to " + reportDir.toAbsolutePath());
        switch (options.mode()) {
            case DRY_RUN -> Log.info("Nothing was written. Re-run with --apply to commit.");
            case APPLY -> Log.info("Run the report again to confirm nothing syncable is left.");
            default -> { }
        }
    }
}
