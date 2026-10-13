package net.mekomsolutions.datasync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatientListTest {

    @TempDir
    Path dir;

    @Test
    void defaultsToEveryPatientInTheSourceInPatientIdOrder() throws Exception {
        try (SyncFixture f = new SyncFixture()) {
            List<String> uuids = PatientList.resolve(Options.parse("report"), f.databases.source());

            // person-admin and the users' persons are not patients
            assertEquals(List.of("person-p1", "person-p2", "person-p3"), uuids);
        }
    }

    @Test
    void combinesCommandLineAndFileNormalizedAndWithoutDuplicates() throws Exception {
        Path file = Files.writeString(dir.resolve("patients.txt"), """
                # patients to retry
                PERSON-P2

                person-p3   # duplicate username
                person-p1
                """);
        Options options = Options.parse("sync", "--patient", "person-p1", "--patients-file", file.toString());

        assertEquals(List.of("person-p1", "person-p2", "person-p3"), PatientList.resolve(options, null));
    }
}
