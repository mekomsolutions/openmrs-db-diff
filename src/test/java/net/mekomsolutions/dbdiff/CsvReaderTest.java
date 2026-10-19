package net.mekomsolutions.dbdiff;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvReaderTest {

    @TempDir
    Path dir;

    @Test
    void readsBackWhatCsvWriterWrites() throws Exception {
        Path file = dir.resolve("t.csv");
        List<String> tricky = List.of("a,b", "say \"hi\"", "two\nlines", "");
        try (CsvWriter w = new CsvWriter(file, List.of("x", "y", "z", "w"))) {
            w.row(tricky);
        }

        assertEquals(List.of(List.of("x", "y", "z", "w"), tricky), CsvReader.read(file));
    }

    @Test
    void skipsBlankLinesAndHandlesWindowsLineEnds() {
        assertEquals(List.of(List.of("a", "b"), List.of("c", "")), CsvReader.parse("a,b\r\n\r\nc,\r\n"));
    }
}
