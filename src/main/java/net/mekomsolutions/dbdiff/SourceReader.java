package net.mekomsolutions.dbdiff;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import net.mekomsolutions.dbdiff.IdentityResolver.Side;

/** Reads full rows, with their identities, from the source database. */
final class SourceReader {

    private final Connection source;
    private final IdentityResolver resolver;

    SourceReader(Connection source, IdentityResolver resolver) {
        this.source = source;
        this.resolver = resolver;
    }

    /** Rows of {@code table} whose {@code column} holds one of {@code values}. Rows with a NULL uuid are skipped. */
    List<SourceRow> rowsWhere(Schema.Table table, String column, Collection<?> values) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        String sql = "SELECT * FROM " + Db.q(table.name()) + " WHERE " + Db.q(column) + " IN (";
        for (List<Object> batch : Db.batches(new ArrayList<Object>(new LinkedHashSet<>(values)))) {
            try (PreparedStatement ps = source.prepareStatement(sql + Db.placeholders(batch.size()) + ")")) {
                for (int i = 0; i < batch.size(); i++) {
                    ps.setObject(i + 1, batch.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(Db.readRow(rs, table));
                    }
                }
            }
        }
        List<String> identities = resolver.rowIdentities(Side.SOURCE, table, rows);
        List<SourceRow> result = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            if (identities.get(i) != null) {
                result.add(new SourceRow(identities.get(i), rows.get(i)));
            }
        }
        return result;
    }

    /** The non-null values of {@code column} across {@code rows}, without duplicates. */
    static List<Object> values(Collection<SourceRow> rows, String column) {
        LinkedHashSet<Object> values = new LinkedHashSet<>();
        for (SourceRow row : rows) {
            Object v = row.values().get(column);
            if (v != null) {
                values.add(v);
            }
        }
        return new ArrayList<>(values);
    }
}
