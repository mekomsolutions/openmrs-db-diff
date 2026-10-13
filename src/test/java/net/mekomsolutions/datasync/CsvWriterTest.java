package net.mekomsolutions.datasync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvWriterTest {

    @TempDir
    Path dir;

    @Test
    void quotesOnlyWhatNeedsQuotingAndWritesNullsEmpty() throws Exception {
        Path file = dir.resolve("out.csv");
        try (CsvWriter csv = new CsvWriter(file, List.of("a", "b", "c", "d"))) {
            csv.row(Arrays.asList("plain", "with, comma", "say \"hi\"", null));
            csv.row(List.of("two\nlines", "", "x", "y"));
        }

        assertEquals("""
                a,b,c,d
                plain,"with, comma","say ""hi\"\"",
                "two
                lines",,x,y
                """, Files.readString(file));
    }
}
