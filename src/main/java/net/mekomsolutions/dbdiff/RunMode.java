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
    METADATA
}
