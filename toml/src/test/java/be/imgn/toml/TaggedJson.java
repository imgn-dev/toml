package be.imgn.toml;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Reads the expected results of toml-test: JSON in which every scalar is an object such as {@code {"type": "integer",
 * "value": "42"}}. Only objects, arrays and strings appear in them, so nothing else is read.
 */
final class TaggedJson {

    private final String json;
    private int position;

    private TaggedJson(String json) {
        this.json = json;
    }

    static TomlValue parse(String json) {
        var reader = new TaggedJson(json);
        var value = reader.value();
        reader.skipWhitespace();
        if (reader.position != json.length()) {
            throw new IllegalArgumentException("trailing content at " + reader.position);
        }
        return value;
    }

    private TomlValue value() {
        skipWhitespace();
        return switch (json.charAt(position)) {
            case '{' -> object();
            case '[' -> array();
            default -> throw new IllegalArgumentException("unexpected character at " + position);
        };
    }

    private TomlValue object() {
        expect('{');
        var entries = new LinkedHashMap<String, Object>();
        skipWhitespace();
        if (!match('}')) {
            do {
                skipWhitespace();
                var key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                entries.put(key, json.charAt(position) == '"' ? string() : value());
                skipWhitespace();
            } while (match(','));
            expect('}');
        }
        if (entries.size() == 2
                && entries.get("type") instanceof String type
                && entries.get("value") instanceof String value) {
            return scalar(type, value);
        }
        var table = TomlTable.builder();
        entries.forEach((key, value) -> table.put(key, (TomlValue) value));
        return table.build();
    }

    private TomlValue array() {
        expect('[');
        var values = new ArrayList<TomlValue>();
        skipWhitespace();
        if (!match(']')) {
            do {
                values.add(value());
                skipWhitespace();
            } while (match(','));
            expect(']');
        }
        return new TomlArray(values);
    }

    private String string() {
        expect('"');
        var string = new StringBuilder();
        while (!match('"')) {
            var c = json.charAt(position++);
            if (c != '\\') {
                string.append(c);
                continue;
            }
            var escaped = json.charAt(position++);
            switch (escaped) {
                case 'b' -> string.append('\b');
                case 'f' -> string.append('\f');
                case 'n' -> string.append('\n');
                case 'r' -> string.append('\r');
                case 't' -> string.append('\t');
                case 'u' -> {
                    string.append((char) Integer.parseInt(json, position, position + 4, 16));
                    position += 4;
                }
                default -> string.append(escaped);
            }
        }
        return string.toString();
    }

    private static TomlValue scalar(String type, String value) {
        return switch (type) {
            case "string" -> new TomlString(value);
            case "integer" -> new TomlInteger(Long.parseLong(value));
            case "float" -> new TomlFloat(floatValue(value));
            case "bool" -> new TomlBoolean(Boolean.parseBoolean(value));
            case "datetime" -> new TomlOffsetDateTime(OffsetDateTime.parse(value));
            case "datetime-local" -> new TomlLocalDateTime(LocalDateTime.parse(value));
            case "date-local" -> new TomlLocalDate(LocalDate.parse(value));
            case "time-local" -> new TomlLocalTime(LocalTime.parse(value));
            default -> throw new IllegalArgumentException("unknown type " + type);
        };
    }

    private static double floatValue(String value) {
        return switch (value) {
            case "inf", "+inf" -> Double.POSITIVE_INFINITY;
            case "-inf" -> Double.NEGATIVE_INFINITY;
            case "nan", "+nan", "-nan" -> Double.NaN;
            default -> Double.parseDouble(value);
        };
    }

    private boolean match(char c) {
        if (position < json.length() && json.charAt(position) == c) {
            position++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!match(c)) {
            throw new IllegalArgumentException("expected " + c + " at " + position);
        }
    }

    private void skipWhitespace() {
        while (position < json.length() && Character.isWhitespace(json.charAt(position))) {
            position++;
        }
    }
}
