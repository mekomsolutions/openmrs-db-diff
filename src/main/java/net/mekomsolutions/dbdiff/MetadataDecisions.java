package net.mekomsolutions.dbdiff;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What to do, during a sync, with metadata rows the source has and the target lacks. Read from the decisions file
 * that the {@code metadata} command suggests and a person reviews (see {@link MetadataCheck}):
 *
 * <ul>
 *   <li>{@link Action#MAP}: the target has the same thing under another uuid. Rows pointing at the source row are
 *       copied pointing at that target row instead.
 *   <li>{@link Action#COPY}: the target does not have it. The row is copied along with the first patient that
 *       needs it.
 *   <li>{@link Action#SKIP}: leave it alone. Rows that need it stay blocked.
 * </ul>
 *
 * Metadata rows that are not in the file are treated as SKIP.
 */
final class MetadataDecisions {

    enum Action { MAP, COPY, SKIP }

    record Decision(String table, String sourceUuid, Action action, String targetUuid) {}

    static final List<String> HEADER = List.of("table", "source_uuid", "name", "action", "target_uuid");

    private static final MetadataDecisions NONE = new MetadataDecisions(Map.of());

    /** table -> source uuid -> decision */
    private final Map<String, Map<String, Decision>> decisions;

    private MetadataDecisions(Map<String, Map<String, Decision>> decisions) {
        this.decisions = decisions;
    }

    static MetadataDecisions none() {
        return NONE;
    }

    /** The decisions in {@code file}, or none when the file does not exist. */
    static MetadataDecisions load(Path file) throws IOException {
        if (file == null || !Files.exists(file)) {
            return NONE;
        }
        List<List<String>> records = CsvReader.read(file);
        if (records.isEmpty() || !records.get(0).equals(HEADER)) {
            throw new IllegalArgumentException(file + ": the first line must be " + String.join(",", HEADER));
        }
        Map<String, Map<String, Decision>> decisions = new HashMap<>();
        for (int i = 1; i < records.size(); i++) {
            Decision d = parse(records.get(i), file, i + 1);
            decisions.computeIfAbsent(d.table(), t -> new HashMap<>()).put(d.sourceUuid(), d);
        }
        return new MetadataDecisions(decisions);
    }

    private static Decision parse(List<String> cells, Path file, int line) {
        if (cells.size() != HEADER.size()) {
            throw new IllegalArgumentException(file + " line " + line + ": expected " + HEADER.size() + " cells, found "
                    + cells.size());
        }
        String table = cells.get(0).trim().toLowerCase(Locale.ROOT);
        String sourceUuid = Db.normUuid(cells.get(1));
        String targetUuid = cells.get(4).isBlank() ? null : Db.normUuid(cells.get(4));
        Action action;
        try {
            action = Action.valueOf(cells.get(3).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(file + " line " + line + ": action must be MAP, COPY or SKIP, not '"
                    + cells.get(3) + "'");
        }
        if (table.isEmpty() || sourceUuid.isEmpty()) {
            throw new IllegalArgumentException(file + " line " + line + ": table and source_uuid are required");
        }
        if (action == Action.MAP && targetUuid == null) {
            throw new IllegalArgumentException(file + " line " + line + ": MAP needs a target_uuid");
        }
        return new Decision(table, sourceUuid, action, targetUuid);
    }

    boolean isEmpty() {
        return decisions.isEmpty();
    }

    Action action(String table, String sourceUuid) {
        Decision d = find(table, sourceUuid);
        return d == null ? Action.SKIP : d.action();
    }

    /** The identity to look the row up by in the target: the mapped uuid for MAP, the row's own otherwise. */
    String targetIdentity(Schema.Table table, String identity) {
        Decision d = find(table.name(), identity);
        return d != null && d.action() == Action.MAP ? d.targetUuid() : identity;
    }

    /** Whether some source row of {@code table} is mapped to the target row with this uuid. */
    boolean isMapTarget(Schema.Table table, String targetUuid) {
        return decisions.getOrDefault(table.name(), Map.of()).values().stream()
                .anyMatch(d -> d.action() == Action.MAP && d.targetUuid().equals(targetUuid));
    }

    boolean copies(Schema.Table table, String identity) {
        Decision d = find(table.name(), identity);
        return d != null && d.action() == Action.COPY;
    }

    private Decision find(String table, String sourceUuid) {
        return decisions.getOrDefault(table, Map.of()).get(sourceUuid);
    }
}
