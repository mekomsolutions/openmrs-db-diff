package net.mekomsolutions.dbdiff;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import net.mekomsolutions.dbdiff.TimeShift.Window;

/**
 * Which {@link Window} of rows to shift for a patient. When the time zone convention changed on different dates at
 * different locations, each location gets its own window; a patient gets the window of the location their
 * encounters, visits and identifiers are at. Patients with none of those get the default window, if there is one.
 *
 * <p>The cutoffs file is CSV with the header {@code location,from,before}: a location name, uuid or id (or
 * {@code *} for the default), and the window of {@code date_created} values, from inclusive (may be blank) to before
 * exclusive. Lines starting with # are comments. Locations that are not listed have nothing to shift.
 */
final class TimezoneCutoffs {

    /** Tables whose location_id says where a patient was seen. */
    private static final List<String> LOCATED_TABLES = List.of("encounter", "visit", "patient_identifier");

    /** The window for one patient, or why there is none. */
    record PatientWindow(String location, Window window, String problem) {}

    private final Map<String, Window> byLocationId;
    private final Map<String, String> names;
    private final Window fallback;

    TimezoneCutoffs(Map<String, Window> byLocationId, Map<String, String> names, Window fallback) {
        this.byLocationId = byLocationId;
        this.names = names;
        this.fallback = fallback;
    }

    /**
     * Reads {@code file} (may be null), resolving its locations in {@code db}. {@code fallback} (may be null) is the
     * default window when the file has no {@code *} row.
     */
    static TimezoneCutoffs load(Path file, Window fallback, Connection db) throws IOException, SQLException {
        Map<String, String> idByKey = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        try (PreparedStatement ps = db.prepareStatement("SELECT location_id, name, uuid FROM location");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Object id = rs.getObject(1);
                names.put(Db.key(id), rs.getString(2));
                idByKey.put(Db.key(id), Db.key(id));
                if (rs.getString(3) != null) {
                    idByKey.put(Db.normUuid(rs.getString(3)), Db.key(id));
                }
                if (rs.getString(2) != null) {
                    idByKey.putIfAbsent(rs.getString(2).trim().toLowerCase(Locale.ROOT), Db.key(id));
                }
            }
        }
        Map<String, Window> windows = new HashMap<>();
        if (file != null) {
            for (List<String> row : CsvReader.read(file)) {
                if (row.get(0).isBlank() || row.get(0).trim().startsWith("#")
                        || row.get(0).trim().equalsIgnoreCase("location")) {
                    continue; // blank, comment or header
                }
                if (row.size() < 3) {
                    throw new IllegalArgumentException("Expected location,from,before in " + file + ": " + row);
                }
                String location = row.get(0).trim();
                Window window = new Window(row.get(1).isBlank() ? null : TimeShift.parseInput(row.get(1)),
                        TimeShift.parseInput(row.get(2)));
                if (location.equals("*")) {
                    fallback = window;
                    continue;
                }
                String id = idByKey.get(location.toLowerCase(Locale.ROOT));
                if (id == null) {
                    throw new IllegalArgumentException("Unknown location in " + file + ": " + location);
                }
                windows.put(id, window);
            }
        }
        return new TimezoneCutoffs(windows, names, fallback);
    }

    /** Where {@code record}'s patient was seen and which window applies there. */
    PatientWindow windowFor(PatientRecord record) {
        Set<String> locations = new TreeSet<>();
        record.rows().forEach((table, rows) -> {
            if (LOCATED_TABLES.contains(table.name()) && table.hasColumn("location_id")) {
                rows.forEach(r -> {
                    Object id = r.values().get("location_id");
                    if (id != null) {
                        locations.add(Db.key(id));
                    }
                });
            }
        });
        if (locations.isEmpty()) {
            return fallback != null ? new PatientWindow("*", fallback, null)
                    : new PatientWindow("", null, "no location, and no default window");
        }
        if (locations.size() > 1) {
            return new PatientWindow(describe(locations), null, "seen at several locations: " + describe(locations));
        }
        String location = locations.iterator().next();
        Window window = byLocationId.get(location);
        return new PatientWindow(describe(locations), window, window == null ? "no window for this location" : null);
    }

    private String describe(Set<String> locationIds) {
        return String.join(" ", locationIds.stream().map(id -> names.getOrDefault(id, id)).toList());
    }

    /** Every window, by location name, for the log. */
    Map<String, Window> describeAll() {
        Map<String, Window> all = new TreeMap<>();
        byLocationId.forEach((id, w) -> all.put(names.getOrDefault(id, id), w));
        if (fallback != null) {
            all.put("*", fallback);
        }
        return all;
    }
}
