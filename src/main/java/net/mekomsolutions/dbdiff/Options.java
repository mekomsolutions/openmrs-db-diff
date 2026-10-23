package net.mekomsolutions.dbdiff;

import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Command-line options.
 *
 * @param patientUuids patients given with --patient, possibly empty
 * @param patientsFile file given with --patients-file, or null
 * @param assumeYes skip the APPLY confirmation (--yes)
 * @param timezone what the timezone command shifts, or null for the other commands
 */
record Options(RunMode mode, Path config, List<String> patientUuids, Path patientsFile, boolean assumeYes,
               Timezone timezone) {

    /**
     * Options of the timezone command.
     *
     * @param database "target" or "source": which database of the config file to fix
     * @param from default window start (--from), or null
     * @param before default window end (--before), or null
     * @param cutoffsFile per-location windows (--cutoffs), or null
     * @param skipped table.column values that are never shifted
     * @param toUtc values are local wall-clock times to turn into UTC (--to utc), rather than the reverse
     */
    record Timezone(String database, LocalDateTime from, LocalDateTime before, Path cutoffsFile, ZoneId zone,
                    Set<String> skipped, boolean toUtc) {}

    static final String USAGE = """
            Usage:
              java -jar openmrs-db-diff.jar metadata      [options]   find metadata the target lacks, suggest decisions
              java -jar openmrs-db-diff.jar report        [options]   find missing rows, write the report
              java -jar openmrs-db-diff.jar sync          [options]   dry run: insert, then roll back
              java -jar openmrs-db-diff.jar sync --apply  [options]   insert and commit, patient by patient
              java -jar openmrs-db-diff.jar timezone      [options]   list historical datetimes to shift back to local time
              java -jar openmrs-db-diff.jar timezone --apply [options] shift them, patient by patient

            Options:
              --patient <uuid>[,<uuid>...]   patient (person) uuid to process; repeatable
              --patients-file <file>         file with one patient uuid per line (# starts a comment)
              --config <file>                default: config/sync.properties
              --yes                          with --apply, skip the confirmation prompt

            Timezone options (see the README):
              --zone <zone id>               required: the local time zone, e.g. America/New_York
              --to local|utc                 required: local turns stored UTC into zone time; utc, the reverse
              --cutoffs <file>               per-location windows: CSV location,from,before
              --from <date> --before <date>  default window of date_created values to shift (yyyy-MM-dd[ HH:mm[:ss]])
              --db target|source             database to fix; default: target
              --skip-columns <t.c>[,<t.c>]   never shift these columns, e.g. obs.value_datetime

            Without --patient or --patients-file, every patient in the source database is processed.
            Run metadata first: report and sync copy or map missing metadata only as config/metadata-decisions.csv says.
            """;

    /** Parses the arguments, or throws {@link IllegalArgumentException} saying what is wrong with them. */
    static Options parse(String... args) {
        String command = null;
        Path config = Path.of("config", "sync.properties");
        List<String> patients = new ArrayList<>();
        Path patientsFile = null;
        boolean apply = false;
        boolean yes = false;
        String database = null;
        LocalDateTime from = null;
        LocalDateTime before = null;
        Path cutoffs = null;
        ZoneId zone = null;
        Set<String> skipped = null;
        String to = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "metadata", "report", "sync", "timezone" -> {
                    if (command != null) {
                        throw new IllegalArgumentException("Give only one command: metadata, report, sync or timezone");
                    }
                    command = arg;
                }
                case "--config" -> config = Path.of(valueOf(args, ++i, arg));
                case "--patient" -> {
                    for (String uuid : valueOf(args, ++i, arg).split(",")) {
                        if (!uuid.isBlank()) {
                            patients.add(uuid.trim());
                        }
                    }
                }
                case "--patients-file" -> patientsFile = Path.of(valueOf(args, ++i, arg));
                case "--apply" -> apply = true;
                case "--yes" -> yes = true;
                case "--db" -> {
                    database = valueOf(args, ++i, arg);
                    if (!database.equals("target") && !database.equals("source")) {
                        throw new IllegalArgumentException("--db is target or source, not " + database);
                    }
                }
                case "--from" -> from = TimeShift.parseInput(valueOf(args, ++i, arg));
                case "--before" -> before = TimeShift.parseInput(valueOf(args, ++i, arg));
                case "--cutoffs" -> cutoffs = Path.of(valueOf(args, ++i, arg));
                case "--zone" -> {
                    try {
                        zone = ZoneId.of(valueOf(args, ++i, arg));
                    } catch (DateTimeException e) {
                        throw new IllegalArgumentException("Unknown time zone: " + args[i]);
                    }
                }
                case "--to" -> {
                    to = valueOf(args, ++i, arg);
                    if (!to.equals("local") && !to.equals("utc")) {
                        throw new IllegalArgumentException("--to is local or utc, not " + to);
                    }
                }
                case "--skip-columns" -> {
                    skipped = new LinkedHashSet<>();
                    for (String column : valueOf(args, ++i, arg).split(",")) {
                        if (!column.isBlank()) {
                            skipped.add(column.trim());
                        }
                    }
                }
                default -> throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }
        if (command == null) {
            throw new IllegalArgumentException("Missing command: metadata, report, sync or timezone");
        }
        boolean timezoneOptions = database != null || from != null || before != null || cutoffs != null
                || zone != null || skipped != null || to != null;
        if (timezoneOptions && !command.equals("timezone")) {
            throw new IllegalArgumentException("--db, --from, --before, --cutoffs, --zone, --to and --skip-columns"
                    + " only go with timezone");
        }
        if (command.equals("timezone") && (zone == null || to == null)) {
            throw new IllegalArgumentException("timezone needs --zone and --to to know what to convert");
        }
        if (command.equals("timezone") && cutoffs == null && before == null) {
            throw new IllegalArgumentException("timezone needs --cutoffs or --before to know which rows to shift");
        }
        if (from != null && before == null) {
            throw new IllegalArgumentException("--from needs --before");
        }
        if (command.equals("metadata") && (!patients.isEmpty() || patientsFile != null)) {
            throw new IllegalArgumentException("metadata compares all metadata; it takes no patients");
        }
        if (apply && !command.equals("sync") && !command.equals("timezone")) {
            throw new IllegalArgumentException("--apply only goes with sync or timezone");
        }
        RunMode mode = switch (command) {
            case "metadata" -> RunMode.METADATA;
            case "report" -> RunMode.REPORT;
            case "timezone" -> apply ? RunMode.TIMEZONE_APPLY : RunMode.TIMEZONE;
            default -> apply ? RunMode.APPLY : RunMode.DRY_RUN;
        };
        Timezone timezone = !command.equals("timezone") ? null : new Timezone(
                database == null ? "target" : database, from, before, cutoffs,
                zone, skipped == null ? Set.of() : Set.copyOf(skipped), "utc".equals(to));
        if (timezone != null && before != null) {
            new TimeShift.Window(from, before); // fails early on a window that ends before it starts
        }
        return new Options(mode, config, List.copyOf(patients), patientsFile, yes, timezone);
    }

    private static String valueOf(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("--")) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }
}
