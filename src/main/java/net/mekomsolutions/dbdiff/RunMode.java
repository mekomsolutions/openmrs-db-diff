package net.mekomsolutions.dbdiff;

/** What a run does with the missing rows it finds. */
enum RunMode {
    /** Find and plan only. Nothing is written anywhere but the report. */
    REPORT,
    /** Insert each patient's rows inside a transaction, then roll it back. */
    DRY_RUN,
    /** Insert each patient's rows and commit, one transaction per patient. */
    APPLY,
    /** Compare metadata only, and suggest what to do with metadata the target lacks. Nothing is written. */
    METADATA,
    /** List the historical datetimes that would be shifted back to local time. Nothing is written but the report. */
    TIMEZONE,
    /** Shift historical datetimes back to local time, one transaction per patient. */
    TIMEZONE_APPLY;

    boolean isTimezone() {
        return this == TIMEZONE || this == TIMEZONE_APPLY;
    }
}
