package net.mekomsolutions.datasync;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.mekomsolutions.datasync.IdentityResolver.Side;
import net.mekomsolutions.datasync.SyncPlanner.Plan;
import net.mekomsolutions.datasync.SyncPlanner.PlannedRow;
import net.mekomsolutions.datasync.SyncPlanner.Ref;

/**
 * Copies planned rows into the target inside one transaction. Auto-increment ids are left to the target and every
 * foreign key is rewritten to the target's id for the same row. A reference to a row that is copied later (the
 * person/users cycle, obs groups) is written with a temporary value and fixed once that row exists.
 *
 * <p>In dry-run mode the transaction is rolled back at the end, so the target is left untouched.
 */
final class SyncExecutor {

    record Result(Map<String, Integer> insertedPerTable, int fixups) {}

    private record Fixup(Schema.Table table, Map<String, Object> targetRow, Ref ref) {}

    private final Connection target;
    private final Schema targetSchema;
    private final IdentityResolver resolver;

    SyncExecutor(Connection target, Schema targetSchema, IdentityResolver resolver) {
        this.target = target;
        this.targetSchema = targetSchema;
        this.resolver = resolver;
    }

    Result execute(Plan plan, boolean apply) throws SQLException {
        Map<String, Integer> inserted = new LinkedHashMap<>();
        List<Fixup> fixups = new ArrayList<>();
        boolean autoCommit = target.getAutoCommit();
        target.setAutoCommit(false);
        try (Statement s = target.createStatement()) {
            s.execute("SET FOREIGN_KEY_CHECKS = 0");
        }
        boolean committed = false;
        try {
            for (Schema.Table table : plan.order()) {
                List<PlannedRow> rows = plan.rows().get(table);
                Log.info((apply ? "Inserting " : "Dry run: inserting ") + rows.size() + " rows into " + table.name());
                int n = insertRows(targetSchema.table(table.name()), rows, fixups);
                inserted.put(table.name(), n);
            }
            applyFixups(fixups);
            try (Statement s = target.createStatement()) {
                s.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            if (apply) {
                target.commit();
                committed = true;
                Log.info("Committed " + inserted.values().stream().mapToInt(Integer::intValue).sum() + " rows");
            } else {
                Log.info("Dry run finished without errors, rolling back");
            }
        } finally {
            if (!committed) {
                target.rollback();
            }
            try (Statement s = target.createStatement()) {
                s.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            target.setAutoCommit(autoCommit);
        }
        return new Result(inserted, fixups.size());
    }

    private int insertRows(Schema.Table table, List<PlannedRow> rows, List<Fixup> fixups) throws SQLException {
        List<Schema.Column> cols = table.columns().stream().filter(c -> !c.autoIncrement()).toList();
        Schema.Column autoInc = table.columns().stream().filter(Schema.Column::autoIncrement).findFirst().orElse(null);
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(Db.q(table.name())).append(" (");
        for (int i = 0; i < cols.size(); i++) {
            sql.append(i == 0 ? "" : ",").append(Db.q(cols.get(i).name()));
        }
        sql.append(") VALUES (").append(Db.placeholders(cols.size())).append(")");

        int count = 0;
        try (PreparedStatement ps = target.prepareStatement(sql.toString(), Statement.RETURN_GENERATED_KEYS)) {
            for (PlannedRow p : rows) {
                Map<String, Object> targetRow = new LinkedHashMap<>();
                List<Ref> deferred = new ArrayList<>();
                for (Schema.Column c : cols) {
                    Object v = p.row().values().get(c.name());
                    Ref ref = p.refs().get(c.name());
                    if (ref != null) {
                        Object tv = resolver.valueFor(Side.TARGET, ref.refTable(), ref.fk().refColumn(), ref.refIdentity());
                        if (tv == null) {
                            // Parent is copied later in this run; keep the source value until then.
                            deferred.add(ref);
                        } else {
                            v = tv;
                        }
                    }
                    targetRow.put(c.name(), v);
                }
                for (int i = 0; i < cols.size(); i++) {
                    ps.setObject(i + 1, targetRow.get(cols.get(i).name()));
                }
                try {
                    ps.executeUpdate();
                } catch (SQLException e) {
                    throw new SQLException("Insert into " + table.name() + " failed for row " + describe(p) + ": "
                            + e.getMessage(), e.getSQLState(), e.getErrorCode(), e);
                }
                if (autoInc != null) {
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (keys.next()) {
                            targetRow.put(autoInc.name(), keys.getObject(1));
                        }
                    }
                }
                resolver.registerInserted(table, p.row().identity(), targetRow);
                for (Ref ref : deferred) {
                    fixups.add(new Fixup(table, targetRow, ref));
                }
                count++;
            }
        }
        return count;
    }

    private void applyFixups(List<Fixup> fixups) throws SQLException {
        if (!fixups.isEmpty()) {
            Log.info("Fixing " + fixups.size() + " references to rows that were inserted later");
        }
        for (Fixup f : fixups) {
            Object tv = resolver.valueFor(Side.TARGET, f.ref().refTable(), f.ref().fk().refColumn(), f.ref().refIdentity());
            if (tv == null) {
                throw new SQLException("Could not resolve " + f.table().name() + "." + f.ref().fk().column()
                        + " -> " + f.ref().refTable().name() + " " + f.ref().refIdentity());
            }
            List<String> where = f.table().hasUuid() ? List.of("uuid") : f.table().primaryKey();
            if (where.isEmpty()) {
                throw new SQLException("Cannot update " + f.table().name() + ": it has no primary key or uuid");
            }
            StringBuilder sql = new StringBuilder("UPDATE ").append(Db.q(f.table().name())).append(" SET ")
                    .append(Db.q(f.ref().fk().column())).append(" = ? WHERE ");
            for (int i = 0; i < where.size(); i++) {
                sql.append(i == 0 ? "" : " AND ").append(Db.q(where.get(i))).append(" = ?");
            }
            try (PreparedStatement ps = target.prepareStatement(sql.toString())) {
                ps.setObject(1, tv);
                for (int i = 0; i < where.size(); i++) {
                    ps.setObject(i + 2, f.targetRow().get(where.get(i)));
                }
                if (ps.executeUpdate() != 1) {
                    throw new SQLException("Fix-up of " + f.table().name() + "." + f.ref().fk().column()
                            + " did not match exactly one row");
                }
            }
            f.targetRow().put(f.ref().fk().column(), tv);
        }
    }

    private static String describe(PlannedRow p) {
        Object uuid = p.row().values().get("uuid");
        return uuid != null ? "uuid " + uuid : p.row().identity().replace('\u001F', '/');
    }
}
