package net.mekomsolutions.datasync;

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
            System.exit(new SyncRun(options, SyncConfig.load(options.config())).execute());
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
    }
}
