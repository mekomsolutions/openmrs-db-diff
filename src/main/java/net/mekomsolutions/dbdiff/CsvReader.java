package net.mekomsolutions.dbdiff;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads RFC 4180 CSV, the format {@link CsvWriter} writes, including quoted cells with commas or line breaks. */
final class CsvReader {

    private CsvReader() {}

    /** Every record of {@code file}, header included. Blank lines are skipped. */
    static List<List<String>> read(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    static List<List<String>> parse(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean cellStarted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
                cellStarted = true;
            } else if (c == ',') {
                record.add(cell.toString());
                cell.setLength(0);
                cellStarted = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                endRecord(records, record, cell, cellStarted);
                record = new ArrayList<>();
                cellStarted = false;
            } else {
                cell.append(c);
                cellStarted = true;
            }
        }
        endRecord(records, record, cell, cellStarted);
        return records;
    }

    private static void endRecord(List<List<String>> records, List<String> record, StringBuilder cell,
                                  boolean cellStarted) {
        if (cellStarted || !record.isEmpty()) {
            record.add(cell.toString());
            records.add(record);
        }
        cell.setLength(0);
    }
}
