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
    private final SyncPlanner planner;
    private final SyncExecutor executor;
    private final RunMode mode;

    PatientSyncer(PatientRecordCollector collector, IdentityResolver resolver, SyncPlanner planner,
                  SyncExecutor executor, RunMode mode) {
        this.collector = collector;
        this.resolver = resolver;
        this.planner = planner;
        this.executor = executor;
        this.mode = mode;
    }

    /** A syncer wired to the run's connections, where every lookup cache is shared across patients. */
    static PatientSyncer create(Databases db, Schema source, Schema target, SyncConfig config, RunMode mode) {
        SyncScope scope = new SyncScope(target, config);
        TableLinks links = new TableLinks(source, scope);
        IdentityResolver resolver = new IdentityResolver(source, db.source(), db.targetRead());
        SourceReader reader = new SourceReader(db.source(), resolver);
        return new PatientSyncer(
                new PatientRecordCollector(source, links, reader),
                resolver,
                new SyncPlanner(source, scope, links, reader, resolver),
                new SyncExecutor(db.targetWrite(), scope, resolver),
                mode);
    }

    /**
     * Processes one patient. A failed insert gives a FAILED result that still carries the plan; any other exception
     * is thrown. Either way nothing was written for this patient: inserts only happen inside the executor's
     * transaction, which rolls back on failure.
     */
    PatientResult sync(String patientUuid) throws SQLException {
        Optional<PatientRecord> found = collector.collect(patientUuid);
        if (found.isEmpty()) {
            return PatientResult.notFound(patientUuid);
        }
        PatientRecord record = found.get();
        try {
            SyncPlan plan = planner.plan(missingFromTarget(resolver, record));
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
        return new PatientResult(record.patientUuid(), status, record, plan, inserted, null);
    }
}
