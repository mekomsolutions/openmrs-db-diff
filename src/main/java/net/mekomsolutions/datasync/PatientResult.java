package net.mekomsolutions.datasync;

import java.util.List;
import java.util.Map;

/**
 * The outcome for one patient.
 *
 * @param record the patient's rows in the source, or null if the patient was not found or collecting failed
 * @param plan what was missing and what could be copied, or null if planning was not reached
 * @param inserted rows inserted per table (rolled back again in a dry run)
 * @param error why the patient failed, or null
 */
record PatientResult(String patientUuid, Status status, PatientRecord record, SyncPlan plan,
                     Map<String, Integer> inserted, String error) {

    enum Status {
        /** The source has no patient with this uuid. */
        NOT_FOUND,
        /** Nothing is missing from the target. */
        IN_SYNC,
        /** Rows are missing (report mode). */
        MISSING,
        /** Rows are missing but none of them can be copied; see blocked.csv. */
        BLOCKED,
        /** The dry run inserted the rows without errors and rolled back. */
        DRY_RUN_OK,
        /** The rows were inserted and committed. */
        SYNCED,
        /** Something went wrong; nothing was written for this patient. */
        FAILED
    }

    static PatientResult notFound(String patientUuid) {
        return new PatientResult(patientUuid, Status.NOT_FOUND, null, null, Map.of(), null);
    }

    static PatientResult failed(String patientUuid, Exception e) {
        return failed(patientUuid, null, null, e);
    }

    /** A failure after planning: the record and plan still go into the report. */
    static PatientResult failed(String patientUuid, PatientRecord record, SyncPlan plan, Exception e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        String oneLine = message.replaceAll("\\s*\\R\\s*", " ").strip(); // one log line, one CSV line
        return new PatientResult(patientUuid, Status.FAILED, record, plan, Map.of(), oneLine);
    }

    int sourceRows() {
        return record == null ? 0 : record.size();
    }

    int missing() {
        return plan == null ? 0 : plan.missing();
    }

    int syncable() {
        return plan == null ? 0 : plan.size();
    }

    int blocked() {
        return plan == null ? 0 : plan.blocked().size();
    }

    int insertedTotal() {
        return inserted.values().stream().mapToInt(Integer::intValue).sum();
    }

    List<SyncPlan.BlockedRow> blockedRows() {
        return plan == null ? List.of() : plan.blocked();
    }
}
