package be.imgn.toml;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes a table as a TOML document, to any {@link Appendable}.
 *
 * <p>Each table is written as its entries, then its subtables. A subtable starts with a {@code [header]}, and each
 * table of an array of tables with a {@code [[header]]}, while the values inside an array or an inline table are
 * written inline. A table that has only subtables gets no header: the headers of its subtables define it.
 */
final class Emitter {

    private static final int MAX_FRACTION_DIGITS = 9;
    // Seconds are always written, as TOML 1.0 requires them.
    private static final DateTimeFormatter TIME = new DateTimeFormatterBuilder()
            .appendPattern("HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, MAX_FRACTION_DIGITS, true)
            .toFormatter();

    private final Appendable out;
    private boolean empty = true;

    private Emitter(Appendable out) {
        this.out = out;
    }

    static void write(TomlTable table, Appendable out) throws IOException {
        new Emitter(out).body(List.of(), table);
    }

    static String format(TomlTable table) {
        var out = new StringBuilder();
        try {
            write(table, out);
        } catch (IOException e) {
            throw new AssertionError("a StringBuilder does not throw IOException", e);
        }
        return out.toString();
    }

    private void body(List<String> path, TomlTable table) throws IOException {
        for (var entry : table.entries().entrySet()) {
            if (!isSection(entry.getValue())) {
                key(entry.getKey());
                out.append(" = ");
                value(entry.getValue());
                line();
            }
        }
        for (var entry : table.entries().entrySet()) {
            section(append(path, entry.getKey()), entry.getValue());
        }
    }

    private void section(List<String> path, TomlValue value) throws IOException {
        if (value instanceof TomlTable table) {
            if (table.entries().isEmpty() || !table.entries().values().stream().allMatch(Emitter::isSection)) {
                header("[", path, "]");
            }
            body(path, table);
        } else if (value instanceof TomlArray array && isSection(array)) {
            for (var element : array.values()) {
                header("[[", path, "]]");
                body(path, (TomlTable) element);
            }
        }
    }

    private void header(String open, List<String> path, String close) throws IOException {
        if (!empty) {
            out.append('\n');
        }
        out.append(open);
        for (var i = 0; i < path.size(); i++) {
            if (i > 0) {
                out.append('.');
            }
            key(path.get(i));
        }
        out.append(close);
        line();
    }

    private void line() throws IOException {
        out.append('\n');
        empty = false;
    }

    private void value(TomlValue value) throws IOException {
        switch (value) {
            case TomlString(var string) -> string(string);
            case TomlInteger(var integer) -> out.append(Long.toString(integer));
            case TomlFloat(var number) -> out.append(floatLiteral(number));
            case TomlBoolean(var bool) -> out.append(Boolean.toString(bool));
            case TomlOffsetDateTime(var dateTime) -> {
                dateTime(dateTime.toLocalDateTime());
                out.append(dateTime.getOffset().getId());
            }
            case TomlLocalDateTime(var dateTime) -> dateTime(dateTime);
            case TomlLocalDate(var date) -> out.append(date.toString());
            case TomlLocalTime(var time) -> time(time);
            case TomlArray(var values) -> array(values);
            case TomlTable(var entries) -> inlineTable(entries);
        }
    }

    private void dateTime(LocalDateTime dateTime) throws IOException {
        // LocalDate.toString pads the year to four digits, and TomlValue's years have no more.
        out.append(dateTime.toLocalDate().toString()).append('T');
        time(dateTime.toLocalTime());
    }

    private void time(LocalTime time) throws IOException {
        // Not formatTo, which would turn an IOException of the Appendable into a DateTimeException.
        out.append(TIME.format(time));
    }

    private void array(List<TomlValue> values) throws IOException {
        out.append('[');
        for (var i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            value(values.get(i));
        }
        out.append(']');
    }

    private void inlineTable(Map<String, TomlValue> entries) throws IOException {
        if (entries.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append("{ ");
        var first = true;
        for (var entry : entries.entrySet()) {
            if (!first) {
                out.append(", ");
            }
            first = false;
            key(entry.getKey());
            out.append(" = ");
            value(entry.getValue());
        }
        out.append(" }");
    }

    private void key(String key) throws IOException {
        if (!key.isEmpty() && key.chars().allMatch(Emitter::isBareKeyChar)) {
            out.append(key);
        } else {
            string(key);
        }
    }

    private void string(String string) throws IOException {
        out.append('"');
        for (var i = 0; i < string.length(); i++) {
            var c = string.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\f' -> out.append("\\f");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < ' ' || c == '\u007F') {
                        out.append("\\u").append(Requirements.hex(c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static String floatLiteral(double number) {
        if (Double.isNaN(number)) {
            return "nan";
        }
        if (Double.isInfinite(number)) {
            return number > 0 ? "inf" : "-inf";
        }
        // Double.toString always writes a digit on both sides of the decimal point, as TOML requires.
        return Double.toString(number);
    }

    /** Whether a value is written as a table or an array of tables under a header, rather than inline. */
    private static boolean isSection(TomlValue value) {
        return value instanceof TomlTable
                || (value instanceof TomlArray(var values)
                        && !values.isEmpty()
                        && values.stream().allMatch(TomlTable.class::isInstance));
    }

    private static boolean isBareKeyChar(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
    }

    private static List<String> append(List<String> path, String key) {
        var appended = new ArrayList<>(path);
        appended.add(key);
        return appended;
    }
}
