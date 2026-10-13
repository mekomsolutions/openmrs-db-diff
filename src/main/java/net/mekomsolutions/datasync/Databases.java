package net.mekomsolutions.datasync;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * The three connections a run uses. Only {@link #targetWrite()} ever writes; the target is also read through a
 * separate read-only connection, so lookups never see rows of a transaction that may still roll back.
 */
final class Databases implements AutoCloseable {

    private static final int VALIDATION_TIMEOUT_SECONDS = 5;

    private final Connection source;
    private final Connection targetRead;
    private final Connection targetWrite;

    private Databases(Connection source, Connection targetRead, Connection targetWrite) {
        this.source = source;
        this.targetRead = targetRead;
        this.targetWrite = targetWrite;
    }

    static Databases open(SyncConfig config) throws SQLException {
        Connection source = readOnly(config.source().connect());
        try {
            Connection targetRead = readOnly(config.target().connect());
            try {
                return new Databases(source, targetRead, config.target().connect());
            } catch (SQLException e) {
                targetRead.close();
                throw e;
            }
        } catch (SQLException e) {
            source.close();
            throw e;
        }
    }

    Connection source() {
        return source;
    }

    Connection targetRead() {
        return targetRead;
    }

    Connection targetWrite() {
        return targetWrite;
    }

    /** Whether every connection still works; a run stops when one is lost rather than failing every patient. */
    boolean allValid() throws SQLException {
        return source.isValid(VALIDATION_TIMEOUT_SECONDS) && targetRead.isValid(VALIDATION_TIMEOUT_SECONDS)
                && targetWrite.isValid(VALIDATION_TIMEOUT_SECONDS);
    }

    @Override
    public void close() throws SQLException {
        try (source; targetRead; targetWrite) {
            // closes all three, even if one fails
        }
    }

    private static Connection readOnly(Connection connection) throws SQLException {
        connection.setReadOnly(true);
        return connection;
    }
}
