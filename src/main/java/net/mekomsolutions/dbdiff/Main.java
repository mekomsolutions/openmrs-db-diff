package net.mekomsolutions.dbdiff;

/** Entry point; see {@link Options#USAGE}. */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println();
            System.err.print(Options.USAGE);
            System.exit(2);
            return;
        }
        try {
            SyncConfig config = SyncConfig.load(options.config());
            if (!options.mode().isTimezone()) {
                System.exit(new SyncRun(options, config).execute());
            }
            System.exit(switch (options.timezone().action()) {
                case TABLES -> new TableTimezoneRun(options, config).execute();
                case UNDO -> new TimezoneUndo(options, config).execute();
                case SUGGEST_SITE_KEYS -> new SiteKeySuggester(options, config).execute();
            });
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
    }
}
