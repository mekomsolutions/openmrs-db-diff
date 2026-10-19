package net.mekomsolutions.dbdiff;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Set;

/**
 * The fixture source and target databases, opened the way a run opens them. The source has two patients the target
 * is missing data for (person-p1 and person-p2) and one whose copy fails on a duplicate username (person-p3).
 */
final class SyncFixture implements AutoCloseable {

    /** Metadata tables, as in src/test/resources/fixtures/excluded-tables.txt. */
    static final Set<String> EXCLUDED = Set.of("concept", "location", "encounter_type", "role");
    static final Set<String> METADATA = EXCLUDED;

    final TestDatabase source;
    final TestDatabase target;
    final SyncConfig config;
    final Databases databases;
    final Schema sourceSchema;
    final Schema targetSchema;

    SyncFixture(Path outputDir) throws SQLException {
        source = TestDatabase.withFixtures("data-source.sql");
        target = TestDatabase.withFixtures("data-target.sql");
        config = new SyncConfig(settings("source", source), settings("target", target), EXCLUDED, outputDir, METADATA,
                null);
        databases = Databases.open(config);
        sourceSchema = source.schema();
        targetSchema = target.schema();
    }

    SyncFixture() throws SQLException {
        this(Path.of("unused"));
    }

    PatientSyncer syncer(RunMode mode) {
        return PatientSyncer.create(databases, sourceSchema, targetSchema, config, mode);
    }

    SyncScope scope() {
        return new SyncScope(targetSchema, config);
    }

    TableLinks links() {
        return new TableLinks(sourceSchema, scope());
    }

    IdentityResolver resolver() {
        return new IdentityResolver(sourceSchema, databases.source(), databases.targetRead());
    }

    /** The uuid of the target row that {@code sql} points at, e.g. the creator of a copied row. */
    String targetValue(String sql) throws SQLException {
        return target.queryString(sql);
    }

    private static SyncConfig.DbSettings settings(String label, TestDatabase db) {
        return new SyncConfig.DbSettings(label, db.url(), "", "");
    }

    @Override
    public void close() throws SQLException {
        try (source; target; databases) {
            // closes all three
        }
    }
}
