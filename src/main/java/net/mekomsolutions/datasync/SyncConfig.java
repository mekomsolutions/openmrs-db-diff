package net.mekomsolutions.datasync;

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

/** Settings read from a properties file (see config/sync.properties.example). */
record SyncConfig(DbSettings source, DbSettings target, Set<String> excludedTables, Path outputDir) {

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
        Path excludedFile = base.resolve(p.getProperty("excludedTablesFile", "excluded-tables.txt"));
        Set<String> excluded = new TreeSet<>();
        for (String line : Files.readAllLines(excludedFile, StandardCharsets.UTF_8)) {
            String t = line.replace("\"", "").trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                excluded.add(t.toLowerCase(Locale.ROOT));
            }
        }
        return new SyncConfig(db(p, "source"), db(p, "target"), excluded,
                Path.of(p.getProperty("outputDir", "output")));
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
}
