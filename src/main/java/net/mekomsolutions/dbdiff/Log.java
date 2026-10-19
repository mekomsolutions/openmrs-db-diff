package net.mekomsolutions.dbdiff;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

final class Log {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Log() {}

    static void info(String msg) {
        System.out.println(LocalTime.now().format(TIME) + "  " + msg);
    }

    static void warn(String msg) {
        System.out.println(LocalTime.now().format(TIME) + "  WARN " + msg);
    }
}
