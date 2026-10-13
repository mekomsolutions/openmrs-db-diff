package net.mekomsolutions.datasync;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.mekomsolutions.datasync.IdentityResolver.Side;

/** Finds the rows of a table that exist in the source database but not in the target. */
final class MissingRowFinder {

    record MissingRow(String identity, Map<String, Object> values) {}

    record TableDiff(Schema.Table table, long sourceRows, List<MissingRow> missing) {}

    private final Connection sourceStream;
    private final Connection targetStream;
    private final Connection source;
    private final IdentityResolver resolver;

    /**
     * @param sourceStream, targetStream connections used only for streaming uuid lists
     * @param source connection used for fetching full rows
     */
    MissingRowFinder(Connection sourceStream, Connection targetStream, Connection source, IdentityResolver resolver) {
        this.sourceStream = sourceStream;
        this.targetStream = targetStream;
        this.source = source;
        this.resolver = resolver;
    }

    TableDiff diff(Schema.Table table) throws SQLException {
        return table.hasUuid() ? diffByUuid(table) : diffByIdentity(table);
    }

    private TableDiff diffByUuid(Schema.Table table) throws SQLException {
        UuidDiff d;
        try {
            d = mergeDiff(table);
        } catch (OutOfOrderException e) {
            Log.warn(table.name() + ": uuid sort order differs from Java's, falling back to an in-memory comparison");
            d = hashDiff(table);
        }
        List<MissingRow> rows = fetchRows(table, d.missing);
        return new TableDiff(table, d.sourceRows, rows);
    }

    private record UuidDiff(long sourceRows, List<String> missing) {}

    private static final class OutOfOrderException extends RuntimeException {}

    /** Walks both uuid lists in sorted order at once, so memory stays flat even for obs. */
    private UuidDiff mergeDiff(Schema.Table table) throws SQLException {
        String sql = "SELECT uuid FROM " + Db.q(table.name()) + " WHERE uuid IS NOT NULL ORDER BY uuid";
        List<String> missing = new ArrayList<>();
        long count = 0;
        try (PreparedStatement ps1 = Db.streaming(sourceStream, sql);
             PreparedStatement ps2 = Db.streaming(targetStream, sql);
             ResultSet src = ps1.executeQuery();
             ResultSet tgt = ps2.executeQuery()) {
            SortedCursor a = new SortedCursor(src);
            SortedCursor b = new SortedCursor(tgt);
            String x = a.next();
            String y = b.next();
            while (x != null) {
                int cmp = y == null ? -1 : x.compareTo(y);
                if (cmp < 0) {
                    missing.add(x);
                    count++;
                    x = a.next();
                } else if (cmp == 0) {
                    count++;
                    x = a.next();
                } else {
                    y = b.next();
                }
            }
        }
        return new UuidDiff(count, missing);
    }

    private UuidDiff hashDiff(Schema.Table table) throws SQLException {
        String sql = "SELECT uuid FROM " + Db.q(table.name()) + " WHERE uuid IS NOT NULL";
        Set<String> inTarget = new HashSet<>();
        try (PreparedStatement ps = Db.streaming(targetStream, sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                inTarget.add(Db.normUuid(rs.getString(1)));
            }
        }
        List<String> missing = new ArrayList<>();
        long count = 0;
        try (PreparedStatement ps = Db.streaming(sourceStream, sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                count++;
                String u = Db.normUuid(rs.getString(1));
                if (!inTarget.contains(u)) {
                    missing.add(u);
                }
            }
        }
        return new UuidDiff(count, missing);
    }

    /** Reads lower-cased uuids and fails if MySQL's collation order disagrees with Java string order. */
    private static final class SortedCursor {
        private final ResultSet rs;
        private String last;

        SortedCursor(ResultSet rs) {
            this.rs = rs;
        }

        String next() throws SQLException {
            if (!rs.next()) {
                return null;
            }
            String u = Db.normUuid(rs.getString(1));
            if (last != null && u.compareTo(last) < 0) {
                throw new OutOfOrderException();
            }
            last = u;
            return u;
        }
    }

    private List<MissingRow> fetchRows(Schema.Table table, List<String> uuids) throws SQLException {
        List<MissingRow> rows = new ArrayList<>();
        List<Schema.Column> cols = new ArrayList<>(table.columns());
        for (int i = 0; i < uuids.size(); i += Db.BATCH) {
            List<String> batch = uuids.subList(i, Math.min(uuids.size(), i + Db.BATCH));
            String sql = "SELECT * FROM " + Db.q(table.name()) + " WHERE uuid IN (" + Db.placeholders(batch.size()) + ")";
            try (PreparedStatement ps = source.prepareStatement(sql)) {
                for (int j = 0; j < batch.size(); j++) {
                    ps.setString(j + 1, batch.get(j));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (Schema.Column c : cols) {
                            row.put(c.name(), Db.read(rs, rs.findColumn(c.name()), c));
                        }
                        rows.add(new MissingRow(Db.normUuid(row.get("uuid")), row));
                    }
                }
            }
        }
        rows.sort(byPrimaryKey(table));
        return rows;
    }

    private TableDiff diffByIdentity(Schema.Table table) throws SQLException {
        IdentityResolver.LoadedTable src = resolver.load(Side.SOURCE, table);
        Set<String> inTarget = resolver.load(Side.TARGET, table).byIdentity().keySet();
        List<MissingRow> missing = new ArrayList<>();
        for (int i = 0; i < src.rows.size(); i++) {
            String id = src.identity(i);
            if (!inTarget.contains(id)) {
                missing.add(new MissingRow(id, src.rows.get(i)));
            }
        }
        missing.sort(byPrimaryKey(table));
        return new TableDiff(table, src.rows.size(), missing);
    }

    /** Source primary key order, so parents usually come before children that point at them (obs groups). */
    private static Comparator<MissingRow> byPrimaryKey(Schema.Table table) {
        return (r1, r2) -> {
            for (String col : table.primaryKey()) {
                Object a = r1.values().get(col);
                Object b = r2.values().get(col);
                int c;
                if (a instanceof Number na && b instanceof Number nb) {
                    c = Long.compare(na.longValue(), nb.longValue());
                } else {
                    c = String.valueOf(a).compareTo(String.valueOf(b));
                }
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        };
    }
}
