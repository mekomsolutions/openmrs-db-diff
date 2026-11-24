package net.mekomsolutions.dbdiff;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import net.mekomsolutions.dbdiff.TimezoneTables.Candidate;

/**
 * timezone --suggest-site-keys (read-only): when a database is a merge of several sites' databases, each site's rows
 * of a table usually sit in one block of primary keys. This proposes, per table, the key range of one location's own
 * rows, for use with --exclude-keys after review. Nothing is guessed silently: every range comes with how it was
 * found and how many rows of other locations (or of no known location) it would also cover.
 *
 * <p>A row's location comes from, in order: its own location_id; the location of the encounter or visit it points
 * at; for person and patient rows, and rows that point at a person, the location of that person's visits and
 * encounters ("mixed" when there are several). Tables with none of these get no range; their keys are split into
 * blocks wherever date_created jumps back by more than {@value #BREAK_DAYS} days (where a merge appended another
 * site's history), and listed as comments for a decision by hand.
 */
final class SiteKeySuggester {

    static final int BREAK_DAYS = 30;
    private static final long UNKNOWN = Long.MIN_VALUE;
    private static final long MIXED = Long.MIN_VALUE + 1;
    private static final List<String> LOCATED_PARENTS = List.of("encounter", "visit");
    private static final List<String> PERSON_TABLES = List.of("person", "patient");

    /** Rows of one location in one table. */
    private static final class Block {
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        long rows;

        void add(long key) {
            min = Math.min(min, key);
            max = Math.max(max, key);
            rows++;
        }
    }

    private final Options options;
    private final SyncConfig config;
    private final SyncRun.SchemaReader schemaReader;
    private Map<Long, Long> personLocations;

    SiteKeySuggester(Options options, SyncConfig config) {
        this(options, config, Schema::read);
    }

    SiteKeySuggester(Options options, SyncConfig config, SyncRun.SchemaReader schemaReader) {
        this.options = options;
        this.config = config;
        this.schemaReader = schemaReader;
    }

    int execute() throws SQLException, IOException {
        Options.Timezone tz = options.timezone();
        SyncConfig.DbSettings settings = tz.database().equals("source") ? config.source() : config.target();
        Log.info("Database to read: " + settings.label() + " " + settings.url() + " (read-only)");
        try (Connection conn = settings.connect()) {
            conn.setReadOnly(true);
            Schema schema = schemaReader.read(conn);
            Map<Long, String> names = locationNames(conn);
            long site = resolveLocation(tz.siteLocation(), names, conn);
            String label = names.get(site);
            Log.info("Key ranges of the rows of " + label + " (location " + site + ")"
                    + (tz.before() == null ? "" : ", counting rows created before " + TimeShift.format(tz.before())));
            List<Candidate> candidates = TimezoneTables.select(schema, config, tz.tables(),
                    tz.allTables() || tz.tables().isEmpty());
            Path dir = config.outputDir().resolve(ShiftLog.newRunId() + "-site-keys");
            Files.createDirectories(dir);
            int suggested = 0;
            List<String> manual = new ArrayList<>();
            try (CsvWriter out = new CsvWriter(dir.resolve("site-keys-suggested.csv"), List.of("table", "pk_from",
                    "pk_to", "label", "method", "site_rows", "rows_in_range", "other_location_rows_in_range",
                    "unknown_location_rows_in_range"));
                 CsvWriter blocks = new CsvWriter(dir.resolve("site-key-blocks.csv"),
                         List.of("table", "location", "pk_min", "pk_max", "rows", "method"))) {
                for (Candidate c : candidates) {
                    Schema.Table table = c.table();
                    if (TimezoneTables.EXCLUDED.equals(c.problem())) {
                        Log.info("  " + table.name() + ": skipped, " + c.problem());
                        continue;
                    }
                    if (!TimezoneTables.hasIntegerKey(table)) {
                        continue;
                    }
                    String pk = table.primaryKey().get(0);
                    List<String> methods = methods(schema, table);
                    if (methods.isEmpty()) {
                        Log.info("  " + table.name() + ": no location path, date blocks");
                        List<String> found = dateBlocks(conn, table, pk, tz.before(), blocks);
                        manual.add(table.name());
                        out.row(List.of("# " + table.name() + ": no location path; " + (found.isEmpty() ? "no rows"
                                : "date_created blocks by key: " + String.join("; ", found)
                                + ". Add a range by hand if one is " + label + "'s")));
                        continue;
                    }
                    // the first way that locates at least half the rows; else the last one tried
                    String method = null;
                    Map<Long, Block> byLocation = null;
                    List<long[]> pairs = null;
                    for (String m : methods) {
                        method = m;
                        byLocation = new TreeMap<>();
                        pairs = locate(conn, schema, table, pk, m, tz.before(), byLocation);
                        long total = byLocation.values().stream().mapToLong(b -> b.rows).sum();
                        long unknown = byLocation.containsKey(UNKNOWN) ? byLocation.get(UNKNOWN).rows : 0;
                        if (total == 0 || unknown * 2 <= total) {
                            break;
                        }
                    }
                    Log.info("  " + table.name() + ": " + method);
                    for (Map.Entry<Long, Block> e : byLocation.entrySet()) {
                        blocks.row(List.of(table.name(), locationLabel(e.getKey(), names), "" + e.getValue().min,
                                "" + e.getValue().max, "" + e.getValue().rows, method));
                    }
                    Block own = byLocation.get(site);
                    if (own == null) {
                        out.row(List.of("# " + table.name() + ": no rows of " + label + " (" + method + ")"));
                        continue;
                    }
                    long[] inRange = new long[3]; // site, other, unknown or mixed
                    if (pairs != null) {
                        for (long[] p : pairs) {
                            if (p[0] >= own.min && p[0] <= own.max) {
                                inRange[p[1] == site ? 0 : p[1] == UNKNOWN || p[1] == MIXED ? 2 : 1]++;
                            }
                        }
                    } else {
                        countInRange(conn, schema, table, pk, method, tz.before(), own, site, inRange);
                    }
                    out.row(List.of(table.name(), "" + own.min, "" + own.max, label, method, "" + own.rows,
                            "" + (inRange[0] + inRange[1] + inRange[2]), "" + inRange[1], "" + inRange[2]));
                    suggested++;
                }
            }
            Log.info(suggested + " range(s) suggested" + (manual.isEmpty() ? ""
                    : "; no location path for " + String.join(", ", manual) + " (see the comments)"));
            Log.info("Written to " + dir.toAbsolutePath() + ". Review site-keys-suggested.csv (rows of other"
                    + " locations inside a range would be skipped too), then pass it with --exclude-keys.");
            return 0;
        }
    }

    /** The ways rows of {@code table} can get a location, best first; empty when there is none. */
    private static List<String> methods(Schema schema, Schema.Table table) {
        List<String> methods = new ArrayList<>();
        if (table.hasColumn("location_id")) {
            methods.add("location_id");
        }
        for (String parent : LOCATED_PARENTS) {
            for (Schema.ForeignKey fk : table.foreignKeys()) {
                if (fk.refTable().equalsIgnoreCase(parent) && !fk.refTable().equalsIgnoreCase(table.name())
                        && schema.table(fk.refTable()) != null
                        && schema.table(fk.refTable()).hasColumn("location_id")) {
                    methods.add("via " + fk.column() + " -> " + fk.refTable());
                }
            }
        }
        if (PERSON_TABLES.contains(table.name().toLowerCase(Locale.ROOT))) {
            methods.add("person's visits and encounters");
            return methods;
        }
        String byPerson = personMethod(table);
        if (byPerson != null) {
            methods.add(byPerson);
        }
        return methods;
    }

    private static String personMethod(Schema.Table table) {
        Schema.ForeignKey best = null;
        for (Schema.ForeignKey fk : table.foreignKeys()) {
            if (PERSON_TABLES.contains(fk.refTable().toLowerCase(Locale.ROOT))) {
                if (best == null || List.of("patient_id", "person_id").contains(fk.column().toLowerCase(Locale.ROOT))) {
                    best = fk;
                }
            }
        }
        return best == null ? null : "via " + best.column() + " -> person's visits and encounters";
    }

    /**
     * Fills {@code byLocation}. For person-based methods, also returns every (key, location) pair, so rows in range
     * can be counted without another query; null otherwise.
     */
    private List<long[]> locate(Connection conn, Schema schema, Schema.Table table, String pk, String method,
                                LocalDateTime before, Map<Long, Block> byLocation) throws SQLException {
        if (!method.contains("person")) {
            String sql = "SELECT " + locationExpr(table, method) + ", MIN(t." + Db.q(pk) + "), MAX(t." + Db.q(pk)
                    + "), COUNT(*)" + from(table, method) + dateFilter(table, before, " WHERE ") + " GROUP BY "
                    + locationExpr(table, method);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bindDate(ps, 1, table, before);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long loc = rs.getLong(1);
                        if (rs.wasNull()) {
                            loc = UNKNOWN;
                        }
                        Block b = byLocation.computeIfAbsent(loc, k -> new Block());
                        b.min = Math.min(b.min, rs.getLong(2));
                        b.max = Math.max(b.max, rs.getLong(3));
                        b.rows += rs.getLong(4);
                    }
                }
            }
            return null;
        }
        Map<Long, Long> persons = personLocations(conn, schema);
        String personCol = method.startsWith("via ") ? method.substring(4, method.indexOf(" -> ")) : pk;
        List<long[]> pairs = new ArrayList<>();
        String sql = "SELECT t." + Db.q(pk) + ", t." + Db.q(personCol) + " FROM " + Db.q(table.name()) + " t"
                + dateFilter(table, before, " WHERE ");
        try (PreparedStatement ps = streaming(conn, sql)) {
            bindDate(ps, 1, table, before);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long key = rs.getLong(1);
                    long person = rs.getLong(2);
                    long loc = rs.wasNull() ? UNKNOWN : persons.getOrDefault(person, UNKNOWN);
                    byLocation.computeIfAbsent(loc, k -> new Block()).add(key);
                    pairs.add(new long[] {key, loc});
                }
            }
        }
        return pairs;
    }

    private static void countInRange(Connection conn, Schema schema, Schema.Table table, String pk, String method,
                                     LocalDateTime before, Block own, long site, long[] inRange) throws SQLException {
        String sql = "SELECT " + locationExpr(table, method) + ", COUNT(*)" + from(table, method) + " WHERE t."
                + Db.q(pk) + " BETWEEN ? AND ?" + dateFilter(table, before, " AND ") + " GROUP BY "
                + locationExpr(table, method);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, own.min);
            ps.setLong(2, own.max);
            bindDate(ps, 3, table, before);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long loc = rs.getLong(1);
                    boolean unknown = rs.wasNull();
                    inRange[unknown ? 2 : loc == site ? 0 : 1] += rs.getLong(2);
                }
            }
        }
    }

    private static String locationExpr(Schema.Table table, String method) {
        return method.equals("location_id") ? "t." + Db.q("location_id") : "p." + Db.q("location_id");
    }

    private static String from(Schema.Table table, String method) {
        if (method.equals("location_id")) {
            return " FROM " + Db.q(table.name()) + " t";
        }
        String column = method.substring(4, method.indexOf(" -> "));
        Schema.ForeignKey fk = table.foreignKey(column);
        return " FROM " + Db.q(table.name()) + " t LEFT JOIN " + Db.q(fk.refTable()) + " p ON p."
                + Db.q(fk.refColumn()) + " = t." + Db.q(column);
    }

    private static String dateFilter(Schema.Table table, LocalDateTime before, String keyword) {
        return before == null || !table.hasColumn("date_created") ? ""
                : keyword + "t." + Db.q("date_created") + " < ?";
    }

    private static void bindDate(PreparedStatement ps, int index, Schema.Table table, LocalDateTime before)
            throws SQLException {
        if (before != null && table.hasColumn("date_created")) {
            ps.setObject(index, before);
        }
    }

    /** Location of every person seen in visits or encounters; {@link #MIXED} for those seen at several. */
    private Map<Long, Long> personLocations(Connection conn, Schema schema) throws SQLException {
        if (personLocations != null) {
            return personLocations;
        }
        personLocations = new HashMap<>();
        for (String table : LOCATED_PARENTS) {
            Schema.Table t = schema.table(table);
            if (t == null || !t.hasColumn("patient_id") || !t.hasColumn("location_id")) {
                continue;
            }
            try (PreparedStatement ps = streaming(conn, "SELECT DISTINCT patient_id, location_id FROM "
                    + Db.q(table) + " WHERE location_id IS NOT NULL");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long loc = rs.getLong(2);
                    personLocations.merge(rs.getLong(1), loc, (a, b) -> a.equals(b) ? a : MIXED);
                }
            }
        }
        Log.info("  (located " + personLocations.size() + " persons by their visits and encounters)");
        return personLocations;
    }

    /** Blocks of keys split where date_created jumps back; each written to {@code blocks}, and described. */
    private static List<String> dateBlocks(Connection conn, Schema.Table table, String pk, LocalDateTime before,
                                           CsvWriter blocks) throws SQLException, IOException {
        List<String> found = new ArrayList<>();
        if (!table.hasColumn("date_created")) {
            return found;
        }
        String sql = "SELECT " + Db.q(pk) + ", " + Db.q("date_created") + " FROM " + Db.q(table.name())
                + " ORDER BY " + Db.q(pk);
        long first = 0;
        long lastKey = 0;
        long rows = 0;
        LocalDateTime low = null;
        LocalDateTime high = null;
        int n = 0;
        try (PreparedStatement ps = streaming(conn, sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long key = rs.getLong(1);
                LocalDateTime created;
                try {
                    created = rs.getObject(2, LocalDateTime.class);
                } catch (SQLException e) {
                    created = null;
                }
                if (created == null) {
                    continue;
                }
                if (high != null && created.isBefore(high.minusDays(BREAK_DAYS))) {
                    found.add(block(table, ++n, first, lastKey, rows, low, high, blocks));
                    rows = 0;
                    low = null;
                    high = null;
                }
                if (rows == 0) {
                    first = key;
                }
                rows++;
                lastKey = key;
                low = low == null || created.isBefore(low) ? created : low;
                high = high == null || created.isAfter(high) ? created : high;
            }
        }
        if (rows > 0) {
            found.add(block(table, ++n, first, lastKey, rows, low, high, blocks));
        }
        return found;
    }

    private static String block(Schema.Table table, int n, long first, long last, long rows, LocalDateTime low,
                                LocalDateTime high, CsvWriter blocks) throws IOException {
        String dates = low.toLocalDate() + ".." + high.toLocalDate();
        blocks.row(List.of(table.name(), "block " + n + " (" + dates + ")", "" + first, "" + last, "" + rows,
                "date_created breaks"));
        return first + "-" + last + " (" + rows + " rows, " + dates + ")";
    }

    /** A statement that streams its rows on MySQL instead of loading them all. */
    private static PreparedStatement streaming(Connection conn, String sql) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        if (conn.getMetaData().getURL().startsWith("jdbc:mysql")) {
            ps.setFetchSize(Integer.MIN_VALUE);
        }
        return ps;
    }

    private static Map<Long, String> locationNames(Connection conn) throws SQLException {
        Map<Long, String> names = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT location_id, name FROM location");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                names.put(rs.getLong(1), rs.getString(2));
            }
        }
        return names;
    }

    private static long resolveLocation(String given, Map<Long, String> names, Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT location_id FROM location WHERE uuid = ?")) {
            ps.setString(1, given);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        for (Map.Entry<Long, String> e : names.entrySet()) {
            if (given.equals(e.getKey().toString()) || given.equalsIgnoreCase(e.getValue())) {
                return e.getKey();
            }
        }
        throw new IllegalArgumentException("Unknown location: " + given);
    }

    private static String locationLabel(long id, Map<Long, String> names) {
        return id == UNKNOWN ? "(unknown)" : id == MIXED ? "(several)" : names.getOrDefault(id, "" + id) + " (" + id
                + ")";
    }
}
