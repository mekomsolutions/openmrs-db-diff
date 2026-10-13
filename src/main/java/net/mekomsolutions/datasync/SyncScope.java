package net.mekomsolutions.datasync;

/** Which source tables take part in the sync: those that are not excluded and also exist in the target. */
final class SyncScope {

    private final Schema target;
    private final SyncConfig config;

    SyncScope(Schema target, SyncConfig config) {
        this.target = target;
        this.config = config;
    }

    boolean includes(Schema.Table sourceTable) {
        return !config.isExcluded(sourceTable.name()) && target.table(sourceTable.name()) != null;
    }

    /** The target's definition of a table in scope. */
    Schema.Table targetTable(Schema.Table sourceTable) {
        return target.table(sourceTable.name());
    }
}
