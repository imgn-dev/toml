package be.imgn.toml;

import static java.time.temporal.ChronoUnit.DAYS;
import static java.time.temporal.ChronoUnit.HOURS;
import static java.time.temporal.ChronoUnit.MICROS;
import static java.time.temporal.ChronoUnit.MILLIS;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.time.temporal.ChronoUnit.NANOS;
import static java.time.temporal.ChronoUnit.SECONDS;
import static java.util.Objects.requireNonNull;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The conversions that the code the annotation processor generates binds values with. They are public for that code
 * only: applications bind documents with {@link Toml#parse(String, Class)}.
 *
 * <p>Each conversion takes a value and its path, which locates the value in the document, as
 * {@link TomlMappingException#path()} describes, and throws a {@link TomlMappingException} at that path if the value
 * does not convert.
 *
 * @since 0.1.0
 */
public final class TomlBindings {

    // In decreasing order, which the amounts of a duration must follow.
    private static final List<String> DURATION_UNITS = List.of("d", "h", "m", "s", "ms", "us", "ns");
    private static final List<BigDecimal> DURATION_UNIT_NANOS = Stream.of(
                    DAYS, HOURS, MINUTES, SECONDS, MILLIS, MICROS, NANOS)
            .map(unit -> BigDecimal.valueOf(unit.getDuration().toNanos()))
            .toList();
    // "ms" comes before "m" and "s", so that the longest unit matches.
    private static final Pattern DURATION_AMOUNT = Pattern.compile("(\\d+(?:\\.\\d+)?)(d|h|ms|m|s|us|ns)");
    private static final BigInteger NANOS_PER_SECOND =
            BigInteger.valueOf(SECONDS.getDuration().toNanos());
    private static final double TWO_TO_THE_63 = 0x1p63;
    private static final Map<Class<? extends TomlValue>, String> KINDS = Map.of(
            TomlString.class, "a string",
            TomlInteger.class, "an integer",
            TomlFloat.class, "a float",
            TomlBoolean.class, "a boolean",
            TomlOffsetDateTime.class, "an offset date-time",
            TomlLocalDateTime.class, "a local date-time",
            TomlLocalDate.class, "a local date",
            TomlLocalTime.class, "a local time",
            TomlArray.class, "an array",
            TomlTable.class, "a table");

    private TomlBindings() {}

    /**
     * Returns a value that is a table, after checking that it has no other key than {@code keys}.
     *
     * @param value the value
     * @param path the path of the value
     * @param keys the keys the table may have
     * @return the table
     * @throws TomlMappingException if {@code value} is not a table, or has another key
     * @throws NullPointerException if an argument or a key is null
     * @throws IllegalArgumentException if {@code keys} has the same key twice
     */
    public static TomlTable table(TomlValue value, String path, String... keys) {
        requireNonNull(value, "value");
        requireNonNull(path, "path");
        var known = Set.of(keys);
        if (!(value instanceof TomlTable table)) {
            throw mismatch(value, path, "a table");
        }
        var unknown = table.entries().keySet().stream()
                .filter(key -> !known.contains(key))
                .map(Emitter::formatKey)
                .toList();
        if (!unknown.isEmpty()) {
            throw new TomlMappingException(
                    path, (unknown.size() == 1 ? "unknown key " : "unknown keys ") + String.join(", ", unknown));
        }
        return table;
    }

    /**
     * Converts the value of a key that a table must have.
     *
     * @param <T> the type of the converted value
     * @param table the table
     * @param path the path of the table
     * @param key the key
     * @param converter converts the value of {@code key}
     * @return the converted value
     * @throws TomlMappingException if {@code table} has no value for {@code key}, or if it does not convert
     * @throws NullPointerException if an argument is null
     */
    public static <T> T required(TomlTable table, String path, String key, Converter<? extends T> converter) {
        requireNonNull(table, "table");
        requireNonNull(path, "path");
        requireNonNull(key, "key");
        requireNonNull(converter, "converter");
        var value = table.entries().get(key);
        var keyPath = child(path, key);
        if (value == null) {
            throw new TomlMappingException(keyPath, "missing");
        }
        return converter.convert(value, keyPath);
    }

    /**
     * Converts the value of a key that a table may lack.
     *
     * @param <T> the type of the converted value
     * @param table the table
     * @param path the path of the table
     * @param key the key
     * @param converter converts the value of {@code key}
     * @return the converted value, or null if {@code table} has no value for {@code key}
     * @throws TomlMappingException if the value does not convert
     * @throws NullPointerException if an argument is null
     */
    public static <T> @Nullable T optional(TomlTable table, String path, String key, Converter<? extends T> converter) {
        requireNonNull(table, "table");
        requireNonNull(path, "path");
        requireNonNull(key, "key");
        requireNonNull(converter, "converter");
        var value = table.entries().get(key);
        return value == null ? null : converter.convert(value, child(path, key));
    }

    /**
     * Returns the exception reporting that a record rejected the values it was given, by throwing an exception from its
     * constructor.
     *
     * @param path the path of the table the record was bound from
     * @param cause the exception the constructor threw
     * @return the exception to throw
     * @throws NullPointerException if an argument is null
     */
    public static TomlMappingException rejected(String path, RuntimeException cause) {
        requireNonNull(path, "path");
        requireNonNull(cause, "cause");
        var message = cause.getMessage();
        return new TomlMappingException(path, message == null ? cause.getClass().getName() : message, cause);
    }

    /**
     * Returns the exception reporting that a string is none of those an enum binds from.
     *
     * @param value the string
     * @param path the path of the string
     * @param expected the strings the enum binds from
     * @return the exception to throw
     * @throws NullPointerException if an argument or an expected string is null
     */
    public static TomlMappingException notOneOf(TomlValue value, String path, String... expected) {
        requireNonNull(value, "value");
        requireNonNull(path, "path");
        var strings = Stream.of(expected).map(Emitter::formatString).collect(Collectors.joining(", "));
        return new TomlMappingException(
                path, "expected one of " + strings + ", found " + Emitter.formatString(asString(value, path)));
    }

    /**
     * Returns a converter that checks the type of a value.
     *
     * @param <V> the type of the value
     * @param type the type of the value
     * @return the converter
     * @throws NullPointerException if {@code type} is null
     */
    public static <V extends TomlValue> Converter<V> as(Class<V> type) {
        requireNonNull(type, "type");
        return (value, path) -> {
            requireNonNull(value, "value");
            requireNonNull(path, "path");
            if (!type.isInstance(value)) {
                throw mismatch(value, path, kind(type));
            }
            return type.cast(value);
        };
    }

    /**
     * Returns a converter of an array to a list, which cannot be modified.
     *
     * @param <T> the type of the elements
     * @param element converts each element
     * @return the converter
     * @throws NullPointerException if {@code element} is null
     */
    public static <T> Converter<List<T>> list(Converter<? extends T> element) {
        requireNonNull(element, "element");
        return (value, path) -> {
            var values = array(value, path);
            var list = new ArrayList<T>(values.size());
            for (var i = 0; i < values.size(); i++) {
                list.add(element.convert(values.get(i), index(path, i)));
            }
            return Collections.unmodifiableList(list);
        };
    }

    /**
     * Returns a converter of an array to a set, which keeps the order of the array and cannot be modified.
     *
     * @param <T> the type of the elements
     * @param element converts each element
     * @return the converter, which throws a {@link TomlMappingException} if two elements are equal once converted
     * @throws NullPointerException if {@code element} is null
     */
    public static <T> Converter<Set<T>> set(Converter<? extends T> element) {
        requireNonNull(element, "element");
        return (value, path) -> {
            var values = array(value, path);
            var set = new LinkedHashSet<T>();
            for (var i = 0; i < values.size(); i++) {
                var elementPath = index(path, i);
                if (!set.add(element.convert(values.get(i), elementPath))) {
                    throw new TomlMappingException(elementPath, "duplicate element");
                }
            }
            return Collections.unmodifiableSequencedSet(set);
        };
    }

    /**
     * Returns a converter of a table to a map, which keeps the order of the table and cannot be modified.
     *
     * @param <T> the type of the values
     * @param entry converts the value of each key
     * @return the converter
     * @throws NullPointerException if {@code entry} is null
     */
    public static <T> Converter<Map<String, T>> map(Converter<? extends T> entry) {
        requireNonNull(entry, "entry");
        return (value, path) -> {
            requireNonNull(value, "value");
            requireNonNull(path, "path");
            if (!(value instanceof TomlTable table)) {
                throw mismatch(value, path, "a table");
            }
            var map = new LinkedHashMap<String, T>();
            table.entries().forEach((key, entryValue) -> map.put(key, entry.convert(entryValue, child(path, key))));
            return Collections.unmodifiableSequencedMap(map);
        };
    }

    /**
     * Converts a string.
     *
     * @param value the value
     * @param path the path of the value
     * @return the string
     * @throws TomlMappingException if {@code value} is not a string
     * @throws NullPointerException if an argument is null
     */
    public static String asString(TomlValue value, String path) {
        requireNonNull(value, "value");
        requireNonNull(path, "path");
        if (value instanceof TomlString(var string)) {
            return string;
        }
        throw mismatch(value, path, "a string");
    }

    /**
     * Converts an integer to a {@code long}.
     *
     * @param value the value
     * @param path the path of the value
     * @return the integer
     * @throws TomlMappingException if {@code value} is not an integer
     * @throws NullPointerException if an argument is null
     */
    public static long asLong(TomlValue value, String path) {
        requireNonNull(value, "value");
        requireNonNull(path, "path");
        if (value instanceof TomlInteger(var integer)) {
            return integer;
        }
        throw mismatch(value, path, "an integer");
    }

    /**
     * Converts an integer to an {@code int}.
     *
     * @param value the value
     * @param path the path of the value
     * @return the integer
     * @throws TomlMappingException if {@code value} is not an integer in the range of {@code int}
     * @throws NullPointerException if an argument is null
     */
    public static int asInt(TomlValue value, String path) {
        return (int) inRange(value, path, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Converts an integer to a {@code short}.
     *
     * @param value the value
     * @param path the path of the value
     * @return the integer
     * @throws TomlMappingException if {@code value} is not an integer in the range of {@code short}
     * @throws NullPointerException if an argument is null
     */
    public static short asShort(TomlValue value, String path) {
        return (short) inRange(value, path, Short.MIN_VALUE, Short.MAX_VALUE);
    }

    /**
     * Converts an integer to a {@code byte}.
     *
     * @param value the value
     * @param path the path of the value
     * @return the integer
     * @throws TomlMappingException if {@code value} is not an integer in the range of {@code byte}
     * @throws NullPointerException if an argument is null
     */
    public static byte asByte(TomlValue value, String path) {
        return (byte) inRange(value, path, Byte.MIN_VALUE, Byte.MAX_VALUE);
    }

    /**
     * Converts a float, or an integer that a {@code double} represents exactly, to a {@code double}.
     *
     * @param value the value
     * @param path the path of the value
     * @return the number
     * @throws TomlMappingException if {@code value} is neither a float nor such an integer
     * @throws NullPointerException if an argument is null
     */
    public static double asDouble(TomlValue value, String path) {
        requireNonNull(value, "value");
        requireNonNull(path, "path");
        return switch (value) {
            case TomlFloat(var number) -> number;
            case TomlInteger(var integer) -> {
                double number = integer;
                // A long rounded up to 2^63 converts back to Long.MAX_VALUE, which hides the rounding.
                if (number == TWO_TO_THE_63 || (long) number != integer) {
                    throw new TomlMappingException(path, "the integer " + integer + " has no exact double value");
                }
                yield number;
            }
            default -> throw mismatch(value, path, "a float");
        };
    }

    /**
     * Converts a float in the range of {@code float}, or an integer that a {@code float} represents exactly, to a
     * {@code float}. A float is rounded to the nearest {@code float}.
     *
     * @param value the value
     * @param path the path of the value
     * @return the number
     * @throws TomlMappingException if {@code value} is neither such a float nor such an integer
     * @throws NullPointerException if an argument is null
     */
    public static float asFloat(TomlValue value, String path) {
        requireNonNull(value, "value");
        requireNonNull(path, "path");
        return switch (value) {
            case TomlFloat(var number) -> {
                var rounded = (float) number;
                if (Float.isInfinite(rounded) && !Double.isInfinite(number)) {
                    throw new TomlMappingException(path, "the float " + number + " is out of the range of float");
                }
                yield rounded;
            }
            case TomlInteger(var integer) -> {
                float number = integer;
                // A long rounded up to 2^63 converts back to Long.MAX_VALUE, which hides the rounding.
                if (number == TWO_TO_THE_63 || (long) number != integer) {
                    throw new TomlMappingException(path, "the integer " + integer + " has no exact float value");
                }
                yield number;
            }
            default -> throw mismatch(value, path, "a float");
        };
    }

    /**
     * Converts a boolean.
     *
     * @param value the value
     * @param path the path of the value
     * @return the boolean
     * @throws TomlMappingException if {@code value} is not a boolean
     * @throws NullPointerException if an argument is null
     */
    public static boolean asBoolean(TomlValue value, String path) {
        requireNonNull(value, "value");
        requireNonNull(path, "path");
        if (value instanceof TomlBoolean(var bool)) {
            return bool;
        }
        throw mismatch(value, path, "a boolean");
    }

    /**
     * Converts an offset date-time.
     *
     * @param value the value
     * @param path the path of the value
     * @return the date-time
     * @throws TomlMappingException if {@code value} is not an offset date-time
     * @throws NullPointerException if an argument is null
     */
    public static OffsetDateTime asOffsetDateTime(TomlValue value, String path) {
        requireNonNull(value, "value");
        requireNonNull(path, "path");
        if (value instanceof TomlOffsetDateTime(var dateTime)) {
            return dateTime;
        }
        throw mismatch(value, path, "an offset date-time");
    }

    /**
     * Converts a local date-time.
     *
     * @param value the value
     * @param path the path of the value
     * @return the date-time
     * @throws TomlMappingException if {@code value} is not a local date-time
     * @throws NullPointerException if an argument is null
     */
    public static LocalDateTime asLocalDateTime(TomlValue value, String path) {
        requireNonNull(value, "value");
        requireNonNull(path, "path");
        if (value instanceof TomlLocalDateTime(var dateTime)) {
            return dateTime;
        }
        throw mismatch(value, path, "a local date-time");
    }

    /**
     * Converts a local date.
     *
     * @param value the value
     * @param path the path of the value
     * @return the date
     * @throws TomlMappingException if {@code value} is not a local date
     * @throws NullPointerException if an argument is null
     */
    public static LocalDate asLocalDate(TomlValue value, String path) {
        requireNonNull(value, "value");
        requireNonNull(path, "path");
        if (value instanceof TomlLocalDate(var date)) {
            return date;
        }
        throw mismatch(value, path, "a local date");
    }

    /**
     * Converts a local time.
     *
     * @param value the value
     * @param path the path of the value
     * @return the time
     * @throws TomlMappingException if {@code value} is not a local time
     * @throws NullPointerException if an argument is null
     */
    public static LocalTime asLocalTime(TomlValue value, String path) {
        requireNonNull(value, "value");
        requireNonNull(path, "path");
        if (value instanceof TomlLocalTime(var time)) {
            return time;
        }
        throw mismatch(value, path, "a local time");
    }

    /**
     * Converts a string to a duration. The string is either:
     *
     * <ul>
     *   <li>in ISO-8601, as {@link Duration#parse(CharSequence)} reads it, such as {@code "PT1M30S"} or
     *       {@code "-P2DT3H"}; or
     *   <li>one or more amounts, each followed by its unit, the units in decreasing order and at most once each, after
     *       an optional {@code -} sign, such as {@code "1h30m"}, {@code "2.5s"} or {@code "-500ms"}. An amount is
     *       digits, optionally followed by a dot and more digits. The units are {@code d} (24 hours), {@code h},
     *       {@code m}, {@code s}, {@code ms}, {@code us} and {@code ns}. The total must be a whole number of
     *       nanoseconds.
     * </ul>
     *
     * @param value the value
     * @param path the path of the value
     * @return the duration
     * @throws TomlMappingException if {@code value} is not a string, or not a duration
     * @throws NullPointerException if an argument is null
     */
    public static Duration asDuration(TomlValue value, String path) {
        var string = asString(value, path);
        return duration(string)
                .orElseThrow(() -> new TomlMappingException(
                        path,
                        "expected a duration, as \"PT1M30S\" or \"1m30s\", found " + Emitter.formatString(string)));
    }

    /**
     * Converts a string to a path of the default file system.
     *
     * @param value the value
     * @param path the path of the value
     * @return the path
     * @throws TomlMappingException if {@code value} is not a string, or not a path
     * @throws NullPointerException if an argument is null
     */
    public static Path asPath(TomlValue value, String path) {
        var string = asString(value, path);
        try {
            return Path.of(string);
        } catch (InvalidPathException e) {
            throw new TomlMappingException(
                    path, "invalid path " + Emitter.formatString(string) + ": " + e.getReason(), e);
        }
    }

    /**
     * Converts a string to a URI, as {@link URI#URI(String)} parses it.
     *
     * @param value the value
     * @param path the path of the value
     * @return the URI
     * @throws TomlMappingException if {@code value} is not a string, or not a URI
     * @throws NullPointerException if an argument is null
     */
    public static URI asUri(TomlValue value, String path) {
        var string = asString(value, path);
        try {
            return new URI(string);
        } catch (URISyntaxException e) {
            throw new TomlMappingException(
                    path, "invalid URI " + Emitter.formatString(string) + ": " + e.getReason(), e);
        }
    }

    private static long inRange(TomlValue value, String path, long min, long max) {
        var integer = asLong(value, path);
        if (integer < min || integer > max) {
            throw new TomlMappingException(
                    path, "expected an integer from " + min + " to " + max + ", found " + integer);
        }
        return integer;
    }

    private static List<TomlValue> array(TomlValue value, String path) {
        requireNonNull(value, "value");
        requireNonNull(path, "path");
        if (value instanceof TomlArray(var values)) {
            return values;
        }
        throw mismatch(value, path, "an array");
    }

    private static Optional<Duration> duration(String string) {
        if (string.startsWith("P") || string.startsWith("-P") || string.startsWith("+P")) {
            return isoDuration(string);
        }
        var negative = string.startsWith("-");
        return totalNanos(string, negative ? 1 : 0).flatMap(nanos -> ofNanos(negative ? nanos.negate() : nanos));
    }

    private static Optional<Duration> isoDuration(String string) {
        try {
            return Optional.of(Duration.parse(string));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** Adds up the amounts of a duration from {@code start}, or returns empty if they are not valid. */
    private static Optional<BigDecimal> totalNanos(String string, int start) {
        var matcher = DURATION_AMOUNT.matcher(string);
        var position = start;
        var previousUnit = -1;
        var nanos = BigDecimal.ZERO;
        while (position < string.length()) {
            matcher.region(position, string.length());
            var unit = matcher.lookingAt() ? DURATION_UNITS.indexOf(matcher.group(2)) : -1;
            if (unit <= previousUnit) {
                return Optional.empty();
            }
            previousUnit = unit;
            nanos = nanos.add(new BigDecimal(matcher.group(1)).multiply(DURATION_UNIT_NANOS.get(unit)));
            position = matcher.end();
        }
        return position == start ? Optional.empty() : Optional.of(nanos);
    }

    private static Optional<Duration> ofNanos(BigDecimal nanos) {
        try {
            var seconds = nanos.toBigIntegerExact().divideAndRemainder(NANOS_PER_SECOND);
            return Optional.of(Duration.ofSeconds(seconds[0].longValueExact(), seconds[1].longValue()));
        } catch (ArithmeticException e) {
            return Optional.empty();
        }
    }

    private static String child(String path, String key) {
        return (path.isEmpty() ? "" : path + ".") + Emitter.formatKey(key);
    }

    private static String index(String path, int index) {
        return path + "[" + index + "]";
    }

    private static TomlMappingException mismatch(TomlValue value, String path, String expected) {
        return new TomlMappingException(path, "expected " + expected + ", found " + kind(value.getClass()));
    }

    private static String kind(Class<? extends TomlValue> type) {
        return KINDS.getOrDefault(type, "a value");
    }

    /**
     * Converts a value.
     *
     * @param <T> the type of the converted value
     * @since 0.1.0
     */
    @FunctionalInterface
    public interface Converter<T> {

        /**
         * Converts a value.
         *
         * @param value the value
         * @param path the path of the value
         * @return the converted value
         * @throws TomlMappingException if {@code value} does not convert
         */
        T convert(TomlValue value, String path);
    }
}
