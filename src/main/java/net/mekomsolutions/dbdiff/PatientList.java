package net.mekomsolutions.dbdiff;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Works out which patients a run processes, as normalized person uuids without duplicates. */
final class PatientList {

    private PatientList() {}

    /** The patients named in the options, or every patient in the source when none are named. */
    static List<String> resolve(Options options, Connection source) throws IOException, SQLException {
        Set<String> uuids = new LinkedHashSet<>();
        options.patientUuids().forEach(uuid -> uuids.add(Db.normUuid(uuid)));
        if (options.patientsFile() != null) {
            uuids.addAll(readFile(options));
        }
        if (options.patientUuids().isEmpty() && options.patientsFile() == null) {
            uuids.addAll(allInSource(source));
        }
        return List.copyOf(uuids);
    }

    private static List<String> readFile(Options options) throws IOException {
        List<String> uuids = new ArrayList<>();
        for (String line : Files.readAllLines(options.patientsFile(), StandardCharsets.UTF_8)) {
            String uuid = line.replaceFirst("#.*", "").trim();
            if (!uuid.isEmpty()) {
                uuids.add(Db.normUuid(uuid));
            }
        }
        return uuids;
    }

    private static List<String> allInSource(Connection source) throws SQLException {
        List<String> uuids = new ArrayList<>();
        String sql = "SELECT pe.uuid FROM patient pa JOIN person pe ON pe.person_id = pa.patient_id "
                + "WHERE pe.uuid IS NOT NULL ORDER BY pa.patient_id";
        try (PreparedStatement ps = source.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                uuids.add(Db.normUuid(rs.getString(1)));
            }
        }
        return uuids;
    }
}
