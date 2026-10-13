package net.mekomsolutions.datasync;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import net.mekomsolutions.datasync.MissingRowFinder.TableDiff;
import net.mekomsolutions.datasync.SyncPlanner.Plan;

/**
 * Usage:
 * <pre>
 *   java -jar openmrs-data-sync.jar report [--config config/sync.properties]
 *   java -jar openmrs-data-sync.jar sync   [--config config/sync.properties]            (dry run)
 *   java -jar openmrs-data-sync.jar sync --apply [--yes] [--config config/sync.properties]
 * </pre>
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        String command = null;
        Path config = Path.of("config", "sync.properties");
        boolean apply = false;
        boolean yes = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "report", "sync" -> command = args[i];
                case "--config" -> config = Path.of(args[++i]);
                case "--apply" -> apply = true;
                case "--yes" -> yes = true;
                default -> {
                    usage();
                    System.exit(2);
                }
            }
        }
        if (command == null || (apply && !command.equals("sync"))) {
            usage();
            System.exit(2);
        }
        try {
            System.exit(run(command, SyncConfig.load(config), apply, yes));
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
    }

    static int run(String command, SyncConfig cfg, boolean apply, boolean yes) throws Exception {
        try (Connection source = cfg.source().connect();
             Connection target = cfg.target().connect();
             Connection sourceStream = cfg.source().connect();
             Connection targetStream = cfg.target().connect()) {
            source.setReadOnly(true);
            sourceStream.setReadOnly(true);
            targetStream.setReadOnly(true);

            Log.info("Source (has the extra data): " + cfg.source().label() + " " + cfg.source().url());
            Log.info("Target (source of truth, receives the data): " + cfg.target().label() + " " + cfg.target().url());

            Schema sourceSchema = Schema.read(source);
            Schema targetSchema = Schema.read(target);
            IdentityResolver resolver = new IdentityResolver(sourceSchema, source, target);
            MissingRowFinder finder = new MissingRowFinder(sourceStream, targetStream, source, resolver);

            List<TableDiff> diffs = new ArrayList<>();
            int skipped = 0;
            for (Schema.Table table : sourceSchema.tables()) {
                if (cfg.isExcluded(table.name())) {
                    skipped++;
                    continue;
                }
                if (targetSchema.table(table.name()) == null) {
                    Log.warn("Table " + table.name() + " is not in the target database, skipping it");
                    continue;
                }
                TableDiff d = finder.diff(table);
                diffs.add(d);
                if (!d.missing().isEmpty()) {
                    Log.info(String.format("%-40s %9d rows, %7d missing in target", table.name(), d.sourceRows(),
                            d.missing().size()));
                }
            }
            Log.info("Compared " + diffs.size() + " tables (" + skipped + " excluded)");

            Plan plan = new SyncPlanner(sourceSchema, targetSchema, resolver).plan(diffs);
            int missing = diffs.stream().mapToInt(d -> d.missing().size()).sum();
            Path out = cfg.outputDir().resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            ReportWriter.write(out, diffs, plan);
            Log.info("Missing rows: " + missing + ", syncable: " + plan.size() + ", blocked: " + plan.blocked().size());
            if (!plan.cycleTables().isEmpty()) {
                Log.info("Foreign-key cycle handled with deferred updates at: " + plan.cycleTables());
            }
            Log.info("Report written to " + out.toAbsolutePath());

            if (command.equals("report") || plan.size() == 0) {
                return 0;
            }
            if (apply && !yes && !confirm(cfg, plan.size())) {
                Log.info("Cancelled");
                return 1;
            }
            SyncExecutor.Result result = new SyncExecutor(target, targetSchema, resolver).execute(plan, apply);
            Log.info((apply ? "Inserted " : "Dry run would insert ") + result.insertedPerTable());
            if (apply) {
                Log.info("Run the report again to confirm nothing syncable is left.");
            } else {
                Log.info("Nothing was written. Re-run with --apply to commit.");
            }
            return 0;
        }
    }

    private static boolean confirm(SyncConfig cfg, int rows) throws Exception {
        System.out.print("About to insert " + rows + " rows into " + cfg.target().label() + " (" + cfg.target().url()
                + "). Make sure you have a backup. Type APPLY to continue: ");
        System.out.flush();
        String answer = new BufferedReader(new InputStreamReader(System.in)).readLine();
        return "APPLY".equals(answer == null ? null : answer.trim());
    }

    private static void usage() {
        System.err.println("""
                Usage:
                  java -jar openmrs-data-sync.jar report [--config config/sync.properties]
                  java -jar openmrs-data-sync.jar sync [--config config/sync.properties]          dry run, rolls back
                  java -jar openmrs-data-sync.jar sync --apply [--yes] [--config ...]            writes to the target
                """);
    }
}
