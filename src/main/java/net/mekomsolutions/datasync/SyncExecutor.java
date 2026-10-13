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
import net.mekomsolutions.datasync.SyncPlan.PlannedRow;
import net.mekomsolutions.datasync.SyncPlan.Ref;

/**
 * Copies a plan's rows into the target inside one transaction. Auto-increment ids are left to the target and every
 * foreign key is rewritten to the target's id for the same row. A reference to a row that is copied later (the
 * person/users cycle, obs groups) is written with a temporary value and fixed once that row exists.
 *
 * <p>The transaction is committed only when {@code commit} is true; otherwise (dry run) and on any error it is
 * rolled back, so the target is left untouched.
 */
final class SyncExecutor {

    /** A reference written with a temporary value, to fix once the row it points at is inserted. */
    private record Fixup(Schema.Table table, Map<String, Object> targetRow, Ref ref) {}

    private final Connection target;
    private final SyncScope scope;
    private final IdentityResolver resolver;

    SyncExecutor(Connection target, SyncScope scope, IdentityResolver resolver) {
        this.target = target;
        this.scope = scope;
        this.resolver = resolver;
    }

    /** Inserts the plan's rows and returns how many went into each table. */
    Map<String, Integer> execute(SyncPlan plan, boolean commit) throws SQLException {
        Map<String, Integer> inserted = new LinkedHashMap<>();
        List<Fixup> fixups = new ArrayList<>();
        boolean autoCommit = target.getAutoCommit();
        boolean committed = false;
        target.setAutoCommit(false);
        setForeignKeyChecks(false);
        try {
            for (Schema.Table table : plan.order()) {
                inserted.put(table.name(), insertRows(scope.targetTable(table), plan.rows().get(table), fixups));
            }
            applyFixups(fixups);
            setForeignKeyChecks(true);
            if (commit) {
                target.commit();
                resolver.commitInserts();
                committed = true;
            }
        } finally {
            if (!committed) {
                target.rollback();
                resolver.discardInserts();
            }
            setForeignKeyChecks(true);
            target.setAutoCommit(autoCommit);
        }
        return inserted;
    }

    private int insertRows(Schema.Table table, List<PlannedRow> rows, List<Fixup> fixups) throws SQLException {
        List<Schema.Column> cols = table.columns().stream().filter(c -> !c.autoIncrement()).toList();
        Schema.Column autoInc = table.columns().stream().filter(Schema.Column::autoIncrement).findFirst().orElse(null);
        String sql = "INSERT INTO " + Db.q(table.name()) + " ("
                + String.join(",", cols.stream().map(c -> Db.q(c.name())).toList())
                + ") VALUES (" + Db.placeholders(cols.size()) + ")";

        try (PreparedStatement ps = target.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (PlannedRow row : rows) {
                Map<String, Object> targetRow = new LinkedHashMap<>();
                List<Ref> deferred = new ArrayList<>();
                for (Schema.Column c : cols) {
                    Object value = row.row().values().get(c.name());
                    Ref ref = row.refs().get(c.name());
                    if (ref != null) {
                        Object targetValue = targetValue(ref);
                        if (targetValue == null) {
                            deferred.add(ref); // parent is inserted later; keep the source value until then
                        } else {
                            value = targetValue;
                        }
                    }
                    targetRow.put(c.name(), value);
                }
                for (int i = 0; i < cols.size(); i++) {
                    ps.setObject(i + 1, targetRow.get(cols.get(i).name()));
                }
                try {
                    ps.executeUpdate();
                } catch (SQLException e) {
                    throw new SQLException("Insert into " + table.name() + " failed for row " + row.row().describe()
                            + ": " + e.getMessage(), e.getSQLState(), e.getErrorCode(), e);
                }
                if (autoInc != null) {
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (keys.next()) {
                            targetRow.put(autoInc.name(), keys.getObject(1));
                        }
                    }
                }
                resolver.recordInsert(table, row.row().identity(), targetRow);
                for (Ref ref : deferred) {
                    fixups.add(new Fixup(table, targetRow, ref));
                }
            }
        }
        return rows.size();
    }

    private void applyFixups(List<Fixup> fixups) throws SQLException {
        for (Fixup f : fixups) {
            Object targetValue = targetValue(f.ref());
            if (targetValue == null) {
                throw new SQLException("Could not resolve " + f.table().name() + "." + f.ref().fk().column() + " -> "
                        + f.ref().refTable().name() + " " + IdentityResolver.readable(f.ref().refIdentity()));
            }
            List<String> where = f.table().hasUuid() ? List.of("uuid") : f.table().primaryKey();
            if (where.isEmpty()) {
                throw new SQLException("Cannot update " + f.table().name() + ": it has no primary key or uuid");
            }
            String sql = "UPDATE " + Db.q(f.table().name()) + " SET " + Db.q(f.ref().fk().column()) + " = ? WHERE "
                    + String.join(" AND ", where.stream().map(c -> Db.q(c) + " = ?").toList());
            try (PreparedStatement ps = target.prepareStatement(sql)) {
                ps.setObject(1, targetValue);
                for (int i = 0; i < where.size(); i++) {
                    ps.setObject(i + 2, f.targetRow().get(where.get(i)));
                }
                if (ps.executeUpdate() != 1) {
                    throw new SQLException("Fix-up of " + f.table().name() + "." + f.ref().fk().column()
                            + " did not match exactly one row");
                }
            }
            f.targetRow().put(f.ref().fk().column(), targetValue);
        }
    }

    private Object targetValue(Ref ref) throws SQLException {
        return resolver.valueFor(Side.TARGET, ref.refTable(), ref.fk().refColumn(), ref.refIdentity());
    }

    private void setForeignKeyChecks(boolean on) throws SQLException {
        try (Statement s = target.createStatement()) {
            s.execute("SET FOREIGN_KEY_CHECKS = " + (on ? 1 : 0));
        }
    }
}
