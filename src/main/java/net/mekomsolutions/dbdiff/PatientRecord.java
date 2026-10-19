package net.mekomsolutions.dbdiff;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every source row that belongs to one patient, by table, in the order the tables were reached from the person row.
 */
record PatientRecord(String patientUuid, Map<Schema.Table, List<SourceRow>> rows) {

    Set<Schema.Table> tables() {
        return rows.keySet();
    }

    int size() {
        return rows.values().stream().mapToInt(List::size).sum();
    }
}
