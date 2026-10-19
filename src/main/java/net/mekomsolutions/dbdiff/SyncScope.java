package net.mekomsolutions.dbdiff;

/**
 * Which source tables take part in the sync: those that are not excluded and also exist in the target. Rows of
 * excluded (metadata) tables are only copied when the {@link MetadataDecisions} say so.
 */
final class SyncScope {

    private final Schema target;
    private final SyncConfig config;
    private final MetadataDecisions decisions;

    SyncScope(Schema target, SyncConfig config) {
        this(target, config, MetadataDecisions.none());
    }

    SyncScope(Schema target, SyncConfig config, MetadataDecisions decisions) {
        this.target = target;
        this.config = config;
        this.decisions = decisions;
    }

    boolean includes(Schema.Table sourceTable) {
        return !config.isExcluded(sourceTable.name()) && target.table(sourceTable.name()) != null;
    }

    /** Whether a missing row the patient's data points at may be copied along with it. */
    boolean canPullIn(Schema.Table sourceTable, String identity) {
        return includes(sourceTable) || (config.isMetadata(sourceTable.name())
                && target.table(sourceTable.name()) != null && decisions.copies(sourceTable, identity));
    }

    MetadataDecisions decisions() {
        return decisions;
    }

    /** The target's definition of a table in scope. */
    Schema.Table targetTable(Schema.Table sourceTable) {
        return target.table(sourceTable.name());
    }
}
