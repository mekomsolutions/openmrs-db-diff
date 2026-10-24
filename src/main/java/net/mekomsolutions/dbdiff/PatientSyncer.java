package net.mekomsolutions.dbdiff;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.mekomsolutions.dbdiff.IdentityResolver.Side;
import net.mekomsolutions.dbdiff.PatientResult.Status;

/** Finds, plans and (depending on the mode) copies the missing data of one patient. */
final class PatientSyncer {

    private final PatientRecordCollector collector;
    private final IdentityResolver resolver;
    private final OwnershipCheck ownership;
    private final SyncPlanner planner;
    private final SyncExecutor executor;
    private final RunMode mode;

    PatientSyncer(PatientRecordCollector collector, IdentityResolver resolver, OwnershipCheck ownership,
                  SyncPlanner planner, SyncExecutor executor, RunMode mode) {
        this.collector = collector;
        this.resolver = resolver;
        this.ownership = ownership;
        this.planner = planner;
        this.executor = executor;
        this.mode = mode;
    }

    /** A syncer wired to the run's connections, where every lookup cache is shared across patients. */
    static PatientSyncer create(Databases db, Schema source, Schema target, SyncConfig config, RunMode mode) {
        return create(db, source, target, config, MetadataDecisions.none(), mode);
    }

    /** As {@link #create(Databases, Schema, Schema, SyncConfig, RunMode)}, honouring the metadata decisions. */
    static PatientSyncer create(Databases db, Schema source, Schema target, SyncConfig config,
                                MetadataDecisions decisions, RunMode mode) {
        SyncScope scope = new SyncScope(target, config, decisions);
        TableLinks links = new TableLinks(source, scope);
        IdentityResolver resolver = new IdentityResolver(source, db.source(), db.targetRead());
        SourceReader reader = new SourceReader(db.source(), resolver);
        return new PatientSyncer(
                new PatientRecordCollector(source, links, reader),
                resolver,
                new OwnershipCheck(source, resolver, config.placeholderUuid()),
                new SyncPlanner(source, scope, links, reader, resolver),
                new SyncExecutor(db.targetWrite(), scope, resolver),
                mode);
    }

    /**
     * Processes one patient. Rows the target holds as placeholders are written over like missing rows. A patient
     * whose rows are otherwise attached elsewhere in the target gives a CONFLICT result and nothing is written, in
     * any mode (see {@link OwnershipCheck}). A failed write gives a FAILED result that still carries the plan; any
     * other exception is thrown. Either way nothing was written for this patient: writes only happen inside the
     * executor's transaction, which rolls back on failure.
     */
    PatientResult sync(String patientUuid) throws SQLException {
        Optional<PatientRecord> found = collector.collect(patientUuid);
        if (found.isEmpty()) {
            return PatientResult.notFound(patientUuid);
        }
        PatientRecord record = found.get();
        try {
            Map<Schema.Table, List<SourceRow>> missing = missingFromTarget(resolver, record);
            OwnershipCheck.Findings checked = ownership.check(record, missing);
            SyncPlan plan = planner.plan(missing, checked.placeholders());
            if (!checked.conflicts().isEmpty()) {
                return new PatientResult(record.patientUuid(), Status.CONFLICT, record, plan, checked.conflicts(),
                        Map.of(), null);
            }
            if (plan.missing() == 0) {
                return result(record, plan, Status.IN_SYNC, Map.of());
            }
            if (mode == RunMode.REPORT) {
                return result(record, plan, Status.MISSING, Map.of());
            }
            if (plan.size() == 0) {
                return result(record, plan, Status.BLOCKED, Map.of());
            }
            boolean commit = mode == RunMode.APPLY;
            try {
                Map<String, Integer> inserted = executor.execute(plan, commit);
                return result(record, plan, commit ? Status.SYNCED : Status.DRY_RUN_OK, inserted);
            } catch (SQLException e) {
                return PatientResult.failed(record.patientUuid(), record, plan, e);
            }
        } finally {
            resolver.forget(record.tables());
        }
    }

    /** The rows of {@code record} that the target does not have. */
    static Map<Schema.Table, List<SourceRow>> missingFromTarget(IdentityResolver resolver, PatientRecord record)
            throws SQLException {
        Map<Schema.Table, List<SourceRow>> missing = new LinkedHashMap<>();
        for (Map.Entry<Schema.Table, List<SourceRow>> e : record.rows().entrySet()) {
            Set<String> present = resolver.existing(Side.TARGET, e.getKey(),
                    e.getValue().stream().map(SourceRow::identity).toList());
            List<SourceRow> absent = e.getValue().stream().filter(r -> !present.contains(r.identity())).toList();
            if (!absent.isEmpty()) {
                missing.put(e.getKey(), absent);
            }
        }
        return missing;
    }

    private PatientResult result(PatientRecord record, SyncPlan plan, Status status, Map<String, Integer> inserted) {
        return new PatientResult(record.patientUuid(), status, record, plan, List.of(), inserted, null);
    }
}
