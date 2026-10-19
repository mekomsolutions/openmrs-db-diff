package net.mekomsolutions.dbdiff;

import java.util.Map;

/**
 * A row read from the source database.
 *
 * @param identity what matches this row to the same row in the target (see {@link IdentityResolver})
 * @param values every column, keyed by name
 */
record SourceRow(String identity, Map<String, Object> values) {

    /** The row's uuid, or its identity when the table has no uuid column. For logs and reports. */
    String describe() {
        Object uuid = values.get("uuid");
        return uuid != null ? uuid.toString() : IdentityResolver.readable(identity);
    }
}
