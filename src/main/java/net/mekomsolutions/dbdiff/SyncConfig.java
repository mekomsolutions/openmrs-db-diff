package net.mekomsolutions.dbdiff;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Settings read from a properties file (see config/sync.properties.example).
 *
 * @param metadataTables the excluded tables that hold metadata (concepts, locations, attribute types, ...), as
 *                       opposed to logs, event queues and the like; only these are compared by the metadata check
 *                       and can be mapped or copied
 * @param metadataDecisionsFile reviewed decisions for metadata the target lacks (see {@link MetadataDecisions});
 *                              may not exist, or be null
 */
record SyncConfig(DbSettings source, DbSettings target, Set<String> excludedTables, Path outputDir,
                  Set<String> metadataTables, Path metadataDecisionsFile) {

    SyncConfig(DbSettings source, DbSettings target, Set<String> excludedTables, Path outputDir) {
        this(source, target, excludedTables, outputDir, Set.of(), null);
    }

    record DbSettings(String label, String url, String user, String password) {
        Connection connect() throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }
    }

    static SyncConfig load(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        Path base = file.toAbsolutePath().getParent();
        Set<String> excluded = tableList(base.resolve(p.getProperty("excludedTablesFile", "excluded-tables.txt")));
        Path metadataFile = base.resolve(p.getProperty("metadataTablesFile", "metadata-tables.txt"));
        Set<String> metadata = Files.exists(metadataFile) ? tableList(metadataFile) : Set.of();
        return new SyncConfig(db(p, "source"), db(p, "target"), excluded,
                Path.of(p.getProperty("outputDir", "output")), metadata,
                base.resolve(p.getProperty("metadataDecisionsFile", "metadata-decisions.csv")));
    }

    /** Table names, one per line; blank lines and lines starting with # are ignored. */
    private static Set<String> tableList(Path file) throws IOException {
        Set<String> tables = new TreeSet<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String t = line.replace("\"", "").trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                tables.add(t.toLowerCase(Locale.ROOT));
            }
        }
        return tables;
    }

    private static DbSettings db(Properties p, String prefix) {
        String url = p.getProperty(prefix + ".url");
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Missing " + prefix + ".url in the config file");
        }
        return new DbSettings(p.getProperty(prefix + ".label", prefix), url.trim(),
                p.getProperty(prefix + ".user", "openmrs"), p.getProperty(prefix + ".password", ""));
    }

    boolean isExcluded(String table) {
        return excludedTables.contains(table.toLowerCase(Locale.ROOT));
    }

    /** Whether {@code table} is metadata: listed as such, and excluded from the patient-by-patient sync. */
    boolean isMetadata(String table) {
        return metadataTables.contains(table.toLowerCase(Locale.ROOT)) && isExcluded(table);
    }
}
