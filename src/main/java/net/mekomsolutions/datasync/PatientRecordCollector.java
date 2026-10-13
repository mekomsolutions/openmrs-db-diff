package net.mekomsolutions.datasync;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Collects a patient's record from the source database: the person row, then every row that belongs to it through
 * {@link TableLinks#ownedBy ownership links}, level by level (person, then patient and names, then encounters and
 * visits, then obs and orders, ...). Each level costs one query per link, however many rows it holds.
 */
final class PatientRecordCollector {

    private final Schema source;
    private final TableLinks links;
    private final SourceReader reader;

    PatientRecordCollector(Schema source, TableLinks links, SourceReader reader) {
        this.source = source;
        this.links = links;
        this.reader = reader;
    }

    /** The record of the patient whose person uuid is {@code patientUuid}, or empty if the source has no such patient. */
    Optional<PatientRecord> collect(String patientUuid) throws SQLException {
        Schema.Table person = source.table("person");
        List<SourceRow> people = reader.rowsWhere(person, "uuid", List.of(patientUuid));
        if (people.isEmpty()) {
            return Optional.empty();
        }

        Map<Schema.Table, Map<String, SourceRow>> found = new LinkedHashMap<>();
        Deque<Level> todo = new ArrayDeque<>();
        todo.add(new Level(person, addNew(found, person, people)));
        while (!todo.isEmpty()) {
            Level level = todo.poll();
            for (TableLinks.Link link : links.ownedBy(level.table())) {
                List<Object> keys = SourceReader.values(level.rows(), link.fk().refColumn());
                if (keys.isEmpty()) {
                    continue;
                }
                List<SourceRow> unseen = addNew(found, link.child(), reader.rowsWhere(link.child(), link.fk().column(), keys));
                if (!unseen.isEmpty()) {
                    todo.add(new Level(link.child(), unseen));
                }
            }
        }

        if (!found.containsKey(source.table("patient"))) {
            return Optional.empty(); // a person, but not a patient
        }
        Map<Schema.Table, List<SourceRow>> rows = new LinkedHashMap<>();
        found.forEach((table, byIdentity) -> rows.put(table, new ArrayList<>(byIdentity.values())));
        return Optional.of(new PatientRecord(patientUuid, rows));
    }

    /** Rows of one table reached in the same step. */
    private record Level(Schema.Table table, List<SourceRow> rows) {}

    /** Adds the rows not seen yet (a row can be reached through several links) and returns them. */
    private static List<SourceRow> addNew(Map<Schema.Table, Map<String, SourceRow>> found, Schema.Table table,
                                          List<SourceRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, SourceRow> seen = found.computeIfAbsent(table, t -> new LinkedHashMap<>());
        List<SourceRow> added = new ArrayList<>();
        for (SourceRow row : rows) {
            if (seen.putIfAbsent(row.identity(), row) == null) {
                added.add(row);
            }
        }
        return added;
    }
}
