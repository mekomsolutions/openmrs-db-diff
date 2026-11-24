package net.mekomsolutions.dbdiff;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Locale;

/**
 * Converts datetimes that were stored in the wrong time zone convention: from UTC to the wall-clock time of a zone,
 * or with {@code toUtc} the other way. Daylight saving time comes from the zone rules, not a fixed offset.
 */
final class TimeShift {

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
     * The rows to convert, by {@code date_created} as stored: from {@code from} (inclusive, null for no
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

    /** One value changed: its column, old and new value as text. */
    record Change(String column, String oldValue, String newValue) {}

    private final ZoneId zone;
    private final boolean toUtc;

    /** {@code toUtc}: values are wall-clock times in the zone, to be turned into UTC. */
    TimeShift(ZoneId zone, boolean toUtc) {
        this.zone = zone;
        this.toUtc = toUtc;
    }

    /** The wall-clock time in the zone at the UTC instant {@code utc}. */
    LocalDateTime toLocal(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDateTime();
    }

    /** How a single value converts (see {@link #convert}). */
    enum Kind {
        /** One clear result. */
        OK,
        /** A local time the clocks showed twice (they went back); the earlier offset, its first occurrence, is used. */
        AMBIGUOUS,
        /** A local time that never happened (the clocks jumped over it); the value is left unchanged. */
        GAP
    }

    /** The converted value of {@link #convert}, and whether it was clear-cut. For {@link Kind#GAP}, the input. */
    record Conversion(LocalDateTime value, Kind kind) {}

    /**
     * Converts one value in the direction this shift was made for. Going to UTC, a local time that happened twice is
     * taken at the earlier offset and flagged {@link Kind#AMBIGUOUS};
     * a local time that never happened is left as it is and flagged {@link Kind#GAP}. Going to local time is always
     * {@link Kind#OK}.
     */
    Conversion convert(LocalDateTime value) {
        if (!toUtc) {
            return new Conversion(toLocal(value), Kind.OK);
        }
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(value);
        if (offsets.isEmpty()) {
            return new Conversion(value, Kind.GAP);
        }
        LocalDateTime utc = value.atZone(zone).withEarlierOffsetAtOverlap()
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        return new Conversion(utc, offsets.size() > 1 ? Kind.AMBIGUOUS : Kind.OK);
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

    /** A date or datetime typed on the command line: yyyy-MM-dd[ HH:mm[:ss]]. */
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
