package net.mekomsolutions.dbdiff;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts datetimes that were stored in the wrong time zone: from UTC to the wall-clock time of a zone, or with
 * {@code toUtc} the other way. This is for databases where a period of historical rows was written in one convention
 * and the rest in the other, so that both end up reading the same. Daylight saving time is taken from the zone
 * rules, not a fixed offset.
 *
 * <p>A row is converted when its {@code date_created} falls in the {@link Window} of rows written in the old
 * convention. All its DATETIME columns move, except skipped ones (columns that were written without a time zone,
 * such as date-only answers kept at midnight) and change columns ({@code date_changed}, {@code date_voided},
 * {@code date_retired}) set after the window, which follow the new convention already. DATE, TIME and TIMESTAMP
 * columns, NULLs and the 1970-01-01 00:00:00 placeholder are never touched.
 */
final class TimeShift {

    private static final Set<String> CHANGE_COLUMNS = Set.of("date_changed", "date_voided", "date_retired");
    private static final LocalDateTime PLACEHOLDER = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final DateTimeFormatter FORMAT = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .toFormatter(Locale.ROOT);
    private static final DateTimeFormatter INPUT = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd")
            .optionalStart().appendLiteral(' ').appendPattern("HH:mm").optionalEnd()
            .optionalStart().appendPattern(":ss").optionalEnd()
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
            .toFormatter(Locale.ROOT);

    /**
     * The rows the migration converted, by {@code date_created} as stored: from {@code from} (inclusive, null for no
     * lower bound) to {@code before} (exclusive).
     */
    record Window(LocalDateTime from, LocalDateTime before) {

        Window {
            if (before == null) {
                throw new IllegalArgumentException("A window needs an end (before)");
            }
            if (from != null && !from.isBefore(before)) {
                throw new IllegalArgumentException("from " + format(from) + " is not before " + format(before));
            }
        }

        boolean contains(LocalDateTime t) {
            return t != null && t.isBefore(before) && (from == null || !t.isBefore(from));
        }

        @Override
        public String toString() {
            return (from == null ? "" : format(from)) + " .. " + format(before);
        }
    }

    /** One value to change. */
    record Change(String column, String oldValue, String newValue) {}

    private final ZoneId zone;
    private final Set<String> skipped;
    private final boolean toUtc;

    TimeShift(ZoneId zone, Set<String> skipped) {
        this(zone, skipped, false);
    }

    /** {@code toUtc}: values are wall-clock times in the zone, to be turned into UTC. */
    TimeShift(ZoneId zone, Set<String> skipped, boolean toUtc) {
        this.zone = zone;
        this.skipped = Set.copyOf(skipped.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList());
        this.toUtc = toUtc;
    }

    ZoneId zone() {
        return zone;
    }

    /** The values of {@code row} to shift; empty when the row was not converted by the migration. */
    List<Change> changes(Schema.Table table, Map<String, Object> row, Window window) {
        if (!table.hasColumn("date_created") || !window.contains(parse(row.get("date_created")))) {
            return List.of();
        }
        List<Change> changes = new ArrayList<>();
        for (Schema.Column column : table.columns()) {
            if (!"datetime".equals(column.dataType())
                    || skipped.contains((table.name() + "." + column.name()).toLowerCase(Locale.ROOT))) {
                continue;
            }
            LocalDateTime value = parse(row.get(column.name()));
            if (value == null || value.equals(PLACEHOLDER)) {
                continue;
            }
            if (CHANGE_COLUMNS.contains(column.name().toLowerCase(Locale.ROOT)) && !window.contains(value)) {
                continue;
            }
            changes.add(new Change(column.name(), row.get(column.name()).toString(), format(toUtc ? toUtc(value) : toLocal(value))));
        }
        return changes;
    }

    /** The wall-clock time in the zone at the UTC instant {@code utc}. */
    LocalDateTime toLocal(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDateTime();
    }

    /**
     * The UTC time of the wall-clock time {@code local} in the zone. A time skipped by a DST change is read as if the
     * clock had not moved yet; a time that happened twice is taken at its first occurrence.
     */
    LocalDateTime toUtc(LocalDateTime local) {
        return local.atZone(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** A stored datetime read as text, or null when it is NULL or not a valid datetime (a zero date, say). */
    static LocalDateTime parse(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.toString().replace('T', ' '), FORMAT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** A date or datetime typed on the command line or in the cutoffs file: yyyy-MM-dd[ HH:mm[:ss]]. */
    static LocalDateTime parseInput(String text) {
        try {
            return LocalDateTime.parse(text.trim(), INPUT);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Not a date or datetime (yyyy-MM-dd[ HH:mm[:ss]]): " + text);
        }
    }

    static String format(LocalDateTime t) {
        return t.format(FORMAT);
    }
}
