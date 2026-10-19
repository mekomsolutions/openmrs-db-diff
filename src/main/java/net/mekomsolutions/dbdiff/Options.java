package net.mekomsolutions.dbdiff;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command-line options.
 *
 * @param patientUuids patients given with --patient, possibly empty
 * @param patientsFile file given with --patients-file, or null
 * @param assumeYes skip the APPLY confirmation (--yes)
 */
record Options(RunMode mode, Path config, List<String> patientUuids, Path patientsFile, boolean assumeYes) {

    static final String USAGE = """
            Usage:
              java -jar openmrs-db-diff.jar report        [options]   find missing rows, write the report
              java -jar openmrs-db-diff.jar sync          [options]   dry run: insert, then roll back
              java -jar openmrs-db-diff.jar sync --apply  [options]   insert and commit, patient by patient

            Options:
              --patient <uuid>[,<uuid>...]   patient (person) uuid to process; repeatable
              --patients-file <file>         file with one patient uuid per line (# starts a comment)
              --config <file>                default: config/sync.properties
              --yes                          with --apply, skip the confirmation prompt

            Without --patient or --patients-file, every patient in the source database is processed.
            """;

    /** Parses the arguments, or throws {@link IllegalArgumentException} saying what is wrong with them. */
    static Options parse(String... args) {
        String command = null;
        Path config = Path.of("config", "sync.properties");
        List<String> patients = new ArrayList<>();
        Path patientsFile = null;
        boolean apply = false;
        boolean yes = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "report", "sync" -> {
                    if (command != null) {
                        throw new IllegalArgumentException("Give only one command, report or sync");
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
                default -> throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }
        if (command == null) {
            throw new IllegalArgumentException("Missing command: report or sync");
        }
        if (apply && !command.equals("sync")) {
            throw new IllegalArgumentException("--apply only goes with sync");
        }
        RunMode mode = command.equals("report") ? RunMode.REPORT : apply ? RunMode.APPLY : RunMode.DRY_RUN;
        return new Options(mode, config, List.copyOf(patients), patientsFile, yes);
    }

    private static String valueOf(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("--")) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }
}
