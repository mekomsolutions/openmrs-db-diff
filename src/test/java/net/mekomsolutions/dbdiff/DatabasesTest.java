package net.mekomsolutions.dbdiff;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Set;

import org.junit.jupiter.api.Test;

class DatabasesTest {

    @Test
    void sourceConnectionFailureIncludesSourceDetails() {
        SyncConfig config = new SyncConfig(
                new SyncConfig.DbSettings("h4", "jdbc:bad-source", "openmrs", "password"),
                new SyncConfig.DbSettings("h9", "jdbc:bad-target", "openmrs", "password"),
                Set.of(), Path.of("unused"));

        SQLException e = assertThrows(SQLException.class, () -> Databases.open(config));
        assertTrue(e.getMessage().contains("Cannot connect to source database 'h4'"), e.getMessage());
    }

    @Test
    void targetConnectionFailureIncludesTargetDetails() throws Exception {
        try (TestDatabase source = TestDatabase.withFixtures()) {
            SyncConfig config = new SyncConfig(
                    new SyncConfig.DbSettings("h4", source.url(), "", ""),
                    new SyncConfig.DbSettings("h9", "jdbc:bad-target", "openmrs", "password"),
                    Set.of(), Path.of("unused"));

            SQLException e = assertThrows(SQLException.class, () -> Databases.open(config));
            assertTrue(e.getMessage().contains("Cannot connect to target database 'h9'"), e.getMessage());
        }
    }
}
