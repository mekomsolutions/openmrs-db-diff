package net.mekomsolutions.dbdiff;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Writes RFC 4180 CSV lines; null cells are written empty. */
final class CsvWriter implements AutoCloseable {

    private final Writer out;

    CsvWriter(Path file, List<String> header) throws IOException {
        this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        row(header);
    }

    void row(List<String> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.write(',');
            }
            out.write(escape(cells.get(i)));
        }
        out.write('\n');
    }

    @Override
    public void close() throws IOException {
        out.close();
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
