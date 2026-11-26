package net.mekomsolutions.dbdiff;

import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
     * @param from start of the date_created range to convert (--from), or null
     * @param before end of the range, exclusive (--before), or null
     * @param skipped table.column values that are never converted
     * @param toUtc values are local wall-clock times to turn into UTC (--to utc), rather than the reverse
     * @param action what to do: convert, undo, or suggest site key ranges
     * @param tables tables given with --table or --tables, possibly empty
     * @param allTables every table with DATETIME columns, minus excluded ones (--all-tables)
     * @param excludeKeysFile primary-key ranges never to touch (--exclude-keys), or null
     * @param chunkSize rows per transaction in table mode and undo (--chunk-size)
     * @param runId the run to undo (--run-id), or null
     * @param siteLocation the location whose own rows to find key ranges for (--site-location), or null
     * @param convertEdited also convert rows changed or voided on or after --before (--convert-edited)
     * @param anchors column deciding which rows of a table qualify, instead of date_created (--anchor-column), by
     *                lower-case table name
     */
    record Timezone(String database, LocalDateTime from, LocalDateTime before, ZoneId zone,
                    Set<String> skipped, boolean toUtc, Action action, List<String> tables, boolean allTables,
                    Path excludeKeysFile, int chunkSize, String runId, String siteLocation, boolean convertEdited,
                    Map<String, String> anchors) {

        TimeShift.Window window() {
            return before == null ? null : new TimeShift.Window(from, before);
        }
    }

    /** What the timezone command does. */
    enum Action {
        /** Convert table by table (--table, --tables or --all-tables). */
        TABLES,
        /** Restore old values from the shift log (--undo). */
        UNDO,
        /** Propose primary-key ranges of one location's own rows; read-only (--suggest-site-keys). */
        SUGGEST_SITE_KEYS
    }

    static final int DEFAULT_CHUNK_SIZE = 5000;

    /** Never converted unless --skip-columns says otherwise: date-only answers kept at midnight. */
    static final Set<String> DEFAULT_SKIPPED = Set.of("obs.value_datetime");

    static final String USAGE = """
            Usage:
              java -jar openmrs-db-diff.jar metadata      [options]   find metadata the target lacks, suggest decisions
              java -jar openmrs-db-diff.jar report        [options]   find missing rows, write the report
              java -jar openmrs-db-diff.jar sync          [options]   dry run: insert, then roll back
              java -jar openmrs-db-diff.jar sync --apply  [options]   insert and commit, patient by patient
              java -jar openmrs-db-diff.jar timezone --table <t> [options]  list datetimes to convert (read-only)
              java -jar openmrs-db-diff.jar timezone --table <t> --apply [options]  convert them
              java -jar openmrs-db-diff.jar timezone --undo [options]       restore converted values (add --apply)
              java -jar openmrs-db-diff.jar timezone --suggest-site-keys --site-location <loc>  read-only

            Options:
              --patient <uuid>[,<uuid>...]   patient (person) uuid to process; repeatable
              --patients-file <file>         file with one patient uuid per line (# starts a comment)
              --config <file>                default: config/sync.properties
              --yes                          with --apply, skip the confirmation prompt

            Timezone options (see the README):
              --zone <zone id>               required: the local time zone, e.g. America/New_York
              --to local|utc                 required: local turns stored UTC into zone time; utc, the reverse
              --from <date> --before <date>  range of date_created to convert (yyyy-MM-dd[ HH:mm[:ss]])
              --table <t>, --tables <t>[,<t>]  tables to convert; repeatable
              --all-tables                   every table with DATETIME columns; excluded tables are always skipped
              --exclude-keys <file>          CSV table,pk_from,pk_to,label: key ranges never touched
              --skip-columns <t.c>[,<t.c>]   default: obs.value_datetime ("" for none)
              --chunk-size <n>               rows per transaction with --apply; default 5000
              --convert-edited               also convert rows changed or voided on/after --before
              --anchor-column <t.c>[,<t.c>]  qualify rows of table t by column c instead of date_created
              --db target|source             database to fix; default: target
              --undo                         with --table/--tables/--all-tables and/or --run-id <id>
              --suggest-site-keys --site-location <name|uuid|id>  [--table ...] [--before <date>]

            Without --patient or --patients-file, report and sync process every patient in the source database.
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
        ZoneId zone = null;
        Set<String> skipped = null;
        String to = null;
        List<String> tables = new ArrayList<>();
        boolean allTables = false;
        boolean undo = false;
        boolean suggest = false;
        Path excludeKeys = null;
        Integer chunkSize = null;
        String runId = null;
        String siteLocation = null;
        boolean convertEdited = false;
        Map<String, String> anchors = new LinkedHashMap<>();
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
                case "--table", "--tables" -> {
                    for (String table : valueOf(args, ++i, arg).split(",")) {
                        if (!table.isBlank()) {
                            tables.add(table.trim());
                        }
                    }
                }
                case "--all-tables" -> allTables = true;
                case "--convert-edited" -> convertEdited = true;
                case "--anchor-column" -> {
                    for (String spec : valueOf(args, ++i, arg).split(",")) {
                        String[] parts = spec.trim().split("\\.");
                        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                            throw new IllegalArgumentException("--anchor-column takes table.column, not " + spec);
                        }
                        anchors.put(parts[0].trim().toLowerCase(Locale.ROOT), parts[1].trim());
                    }
                }
                case "--undo" -> undo = true;
                case "--suggest-site-keys" -> suggest = true;
                case "--exclude-keys" -> excludeKeys = Path.of(valueOf(args, ++i, arg));
                case "--run-id" -> runId = valueOf(args, ++i, arg).trim();
                case "--site-location" -> siteLocation = valueOf(args, ++i, arg).trim();
                case "--chunk-size" -> {
                    String value = valueOf(args, ++i, arg);
                    try {
                        chunkSize = Integer.parseInt(value.trim());
                    } catch (NumberFormatException e) {
                        chunkSize = 0;
                    }
                    if (chunkSize < 1) {
                        throw new IllegalArgumentException("--chunk-size is a positive number, not " + value);
                    }
                }
                default -> throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }
        if (command == null) {
            throw new IllegalArgumentException("Missing command: metadata, report, sync or timezone");
        }
        boolean timezoneOptions = database != null || from != null || before != null
                || zone != null || skipped != null || to != null || !tables.isEmpty() || allTables || undo
                || suggest || excludeKeys != null || chunkSize != null || runId != null || siteLocation != null || convertEdited || !anchors.isEmpty();
        if (timezoneOptions && !command.equals("timezone")) {
            throw new IllegalArgumentException("--db, --from, --before, --zone, --to, --skip-columns and"
                    + " the table, undo and site key options only go with timezone");
        }
        Action action = Action.TABLES;
        if (command.equals("timezone")) {
            if (undo && suggest) {
                throw new IllegalArgumentException("Give --undo or --suggest-site-keys, not both");
            }
            action = undo ? Action.UNDO : suggest ? Action.SUGGEST_SITE_KEYS
                    : Action.TABLES;
            if (!tables.isEmpty() && allTables) {
                throw new IllegalArgumentException("Give --table/--tables or --all-tables, not both");
            }
            if (!patients.isEmpty() || patientsFile != null) {
                throw new IllegalArgumentException("timezone works table by table; it takes no patients");
            }
            if (runId != null && action != Action.UNDO) {
                throw new IllegalArgumentException("--run-id only goes with --undo");
            }
            if (siteLocation != null && action != Action.SUGGEST_SITE_KEYS) {
                throw new IllegalArgumentException("--site-location only goes with --suggest-site-keys");
            }
            if ((convertEdited || !anchors.isEmpty()) && action != Action.TABLES) {
                throw new IllegalArgumentException("--convert-edited and --anchor-column only go with converting");
            }
            if (excludeKeys != null && action != Action.TABLES) {
                throw new IllegalArgumentException("--exclude-keys only goes with --table, --tables or --all-tables");
            }
            switch (action) {
                case TABLES -> {
                    if (tables.isEmpty() && !allTables) {
                        throw new IllegalArgumentException("timezone needs --table, --tables or --all-tables");
                    }
                    if (zone == null || to == null) {
                        throw new IllegalArgumentException("timezone needs --zone and --to to know what to convert");
                    }
                    if (before == null) {
                        throw new IllegalArgumentException("timezone needs --before (and usually --from) to know"
                                + " which rows to convert");
                    }
                }
                case UNDO -> {
                    if (tables.isEmpty() && !allTables && runId == null) {
                        throw new IllegalArgumentException("--undo needs --table/--tables, --all-tables or --run-id"
                                + " to know what to restore");
                    }
                }
                case SUGGEST_SITE_KEYS -> {
                    if (siteLocation == null) {
                        throw new IllegalArgumentException("--suggest-site-keys needs --site-location");
                    }
                    if (apply) {
                        throw new IllegalArgumentException("--suggest-site-keys is read-only; it takes no --apply");
                    }
                }
            }
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
        Set<String> skip = skipped != null ? Set.copyOf(skipped) : DEFAULT_SKIPPED;
        Timezone timezone = !command.equals("timezone") ? null : new Timezone(
                database == null ? "target" : database, from, before, zone, skip, "utc".equals(to), action, List.copyOf(tables), allTables, excludeKeys,
                chunkSize == null ? DEFAULT_CHUNK_SIZE : chunkSize, runId, siteLocation, convertEdited,
                Map.copyOf(anchors));
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
