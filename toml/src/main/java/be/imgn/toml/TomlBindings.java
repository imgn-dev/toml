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
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The conversions that the code the annotation processor generates binds and writes values with. They are public for
 * that code only: applications bind tables with {@link Toml#bind(TomlTable, Class)}, and write records with
 * {@link Toml#toTable(Record)}.
 *
 * <p>Each conversion to a Java value takes a value and its {@link Context}, which locates the value in the document,
 * and throws a {@link TomlMappingException} at its path if the value does not convert.
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
     * Returns a value that is a table, after checking that it has no other key than {@code keys}, unless the context
     * ignores unknown keys.
     *
     * @param value the value
     * @param context the context of the value
     * @param keys the keys the table may have
     * @return the table
     * @throws TomlMappingException if {@code value} is not a table, or has another key that the context rejects
     * @throws NullPointerException if an argument or a key is null
     * @throws IllegalArgumentException if {@code keys} has the same key twice
     */
    public static TomlTable table(TomlValue value, Context context, String... keys) {
        requireNonNull(value, "value");
        requireNonNull(context, "context");
        var known = Set.of(keys);
        if (!(value instanceof TomlTable table)) {
            throw mismatch(value, context, "a table");
        }
        if (context.unknownKeys() == UnknownKeys.IGNORE) {
            return table;
        }
        var unknown = table.entries().keySet().stream()
                .filter(key -> !known.contains(key))
                .map(Emitter::formatKey)
                .toList();
        if (!unknown.isEmpty()) {
            throw new TomlMappingException(
                    context.path(),
                    (unknown.size() == 1 ? "unknown key " : "unknown keys ") + String.join(", ", unknown));
        }
        return table;
    }

    /**
     * Converts the value of a key that a table must have.
     *
     * @param <T> the type of the converted value
     * @param table the table
     * @param context the context of the table
     * @param key the key
     * @param converter converts the value of {@code key}
     * @return the converted value
     * @throws TomlMappingException if {@code table} has no value for {@code key}, or if it does not convert
     * @throws NullPointerException if an argument is null
     */
    public static <T> T required(TomlTable table, Context context, String key, Converter<? extends T> converter) {
        requireNonNull(table, "table");
        requireNonNull(context, "context");
        requireNonNull(key, "key");
        requireNonNull(converter, "converter");
        var value = table.entries().get(key);
        var keyContext = child(context, key);
        if (value == null) {
            throw new TomlMappingException(keyContext.path(), "missing");
        }
        return converter.convert(value, keyContext);
    }

    /**
     * Converts the value of a key that a table may lack.
     *
     * @param <T> the type of the converted value
     * @param table the table
     * @param context the context of the table
     * @param key the key
     * @param converter converts the value of {@code key}
     * @return the converted value, or null if {@code table} has no value for {@code key}
     * @throws TomlMappingException if the value does not convert
     * @throws NullPointerException if an argument is null
     */
    public static <T> @Nullable T optional(
            TomlTable table, Context context, String key, Converter<? extends T> converter) {
        requireNonNull(table, "table");
        requireNonNull(context, "context");
        requireNonNull(key, "key");
        requireNonNull(converter, "converter");
        var value = table.entries().get(key);
        return value == null ? null : converter.convert(value, child(context, key));
    }

    /**
     * Returns the exception reporting that a record rejected the values it was given, by throwing an exception from its
     * constructor.
     *
     * @param context the context of the table the record was bound from
     * @param cause the exception the constructor threw
     * @return the exception to throw
     * @throws NullPointerException if an argument is null
     */
    public static TomlMappingException rejected(Context context, RuntimeException cause) {
        requireNonNull(context, "context");
        requireNonNull(cause, "cause");
        var message = cause.getMessage();
        return new TomlMappingException(
                context.path(), message == null ? cause.getClass().getName() : message, cause);
    }

    /**
     * Returns the exception reporting that a string is none of those an enum binds from.
     *
     * @param value the string
     * @param context the context of the string
     * @param expected the strings the enum binds from
     * @return the exception to throw
     * @throws NullPointerException if an argument or an expected string is null
     */
    public static TomlMappingException notOneOf(TomlValue value, Context context, String... expected) {
        requireNonNull(value, "value");
        requireNonNull(context, "context");
        var strings = Stream.of(expected).map(Emitter::formatString).collect(Collectors.joining(", "));
        return new TomlMappingException(
                context.path(),
                "expected one of " + strings + ", found " + Emitter.formatString(asString(value, context)));
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
        return (value, context) -> {
            requireNonNull(value, "value");
            requireNonNull(context, "context");
            if (!type.isInstance(value)) {
                throw mismatch(value, context, kind(type));
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
        return (value, context) -> {
            var values = array(value, context);
            var list = new ArrayList<T>(values.size());
            for (var i = 0; i < values.size(); i++) {
                list.add(element.convert(values.get(i), index(context, i)));
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
        return (value, context) -> {
            var values = array(value, context);
            var set = new LinkedHashSet<T>();
            for (var i = 0; i < values.size(); i++) {
                var elementContext = index(context, i);
                if (!set.add(element.convert(values.get(i), elementContext))) {
                    throw new TomlMappingException(elementContext.path(), "duplicate element");
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
        return (value, context) -> {
            requireNonNull(value, "value");
            requireNonNull(context, "context");
            if (!(value instanceof TomlTable table)) {
                throw mismatch(value, context, "a table");
            }
            var map = new LinkedHashMap<String, T>();
            table.entries().forEach((key, entryValue) -> map.put(key, entry.convert(entryValue, child(context, key))));
            return Collections.unmodifiableSequencedMap(map);
        };
    }

    /**
     * Converts a string.
     *
     * @param value the value
     * @param context the context of the value
     * @return the string
     * @throws TomlMappingException if {@code value} is not a string
     * @throws NullPointerException if an argument is null
     */
    public static String asString(TomlValue value, Context context) {
        requireNonNull(value, "value");
        requireNonNull(context, "context");
        if (value instanceof TomlString(var string)) {
            return string;
        }
        throw mismatch(value, context, "a string");
    }

    /**
     * Converts an integer to a {@code long}.
     *
     * @param value the value
     * @param context the context of the value
     * @return the integer
     * @throws TomlMappingException if {@code value} is not an integer
     * @throws NullPointerException if an argument is null
     */
    public static long asLong(TomlValue value, Context context) {
        requireNonNull(value, "value");
        requireNonNull(context, "context");
        if (value instanceof TomlInteger(var integer)) {
            return integer;
        }
        throw mismatch(value, context, "an integer");
    }

    /**
     * Converts an integer to an {@code int}.
     *
     * @param value the value
     * @param context the context of the value
     * @return the integer
     * @throws TomlMappingException if {@code value} is not an integer in the range of {@code int}
     * @throws NullPointerException if an argument is null
     */
    public static int asInt(TomlValue value, Context context) {
        return (int) inRange(value, context, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Converts an integer to a {@code short}.
     *
     * @param value the value
     * @param context the context of the value
     * @return the integer
     * @throws TomlMappingException if {@code value} is not an integer in the range of {@code short}
     * @throws NullPointerException if an argument is null
     */
    public static short asShort(TomlValue value, Context context) {
        return (short) inRange(value, context, Short.MIN_VALUE, Short.MAX_VALUE);
    }

    /**
     * Converts an integer to a {@code byte}.
     *
     * @param value the value
     * @param context the context of the value
     * @return the integer
     * @throws TomlMappingException if {@code value} is not an integer in the range of {@code byte}
     * @throws NullPointerException if an argument is null
     */
    public static byte asByte(TomlValue value, Context context) {
        return (byte) inRange(value, context, Byte.MIN_VALUE, Byte.MAX_VALUE);
    }

    /**
     * Converts a float, or an integer that a {@code double} represents exactly, to a {@code double}.
     *
     * @param value the value
     * @param context the context of the value
     * @return the number
     * @throws TomlMappingException if {@code value} is neither a float nor such an integer
     * @throws NullPointerException if an argument is null
     */
    public static double asDouble(TomlValue value, Context context) {
        requireNonNull(value, "value");
        requireNonNull(context, "context");
        return switch (value) {
            case TomlFloat(var number) -> number;
            case TomlInteger(var integer) -> {
                double number = integer;
                // A long rounded up to 2^63 converts back to Long.MAX_VALUE, which hides the rounding.
                if (number == TWO_TO_THE_63 || (long) number != integer) {
                    throw new TomlMappingException(
                            context.path(), "the integer " + integer + " has no exact double value");
                }
                yield number;
            }
            default -> throw mismatch(value, context, "a float");
        };
    }

    /**
     * Converts a float in the range of {@code float}, or an integer that a {@code float} represents exactly, to a
     * {@code float}. A float is rounded to the nearest {@code float}.
     *
     * @param value the value
     * @param context the context of the value
     * @return the number
     * @throws TomlMappingException if {@code value} is neither such a float nor such an integer
     * @throws NullPointerException if an argument is null
     */
    public static float asFloat(TomlValue value, Context context) {
        requireNonNull(value, "value");
        requireNonNull(context, "context");
        return switch (value) {
            case TomlFloat(var number) -> {
                var rounded = (float) number;
                if (Float.isInfinite(rounded) && !Double.isInfinite(number)) {
                    throw new TomlMappingException(
                            context.path(), "the float " + number + " is out of the range of float");
                }
                yield rounded;
            }
            case TomlInteger(var integer) -> {
                float number = integer;
                // A long rounded up to 2^63 converts back to Long.MAX_VALUE, which hides the rounding.
                if (number == TWO_TO_THE_63 || (long) number != integer) {
                    throw new TomlMappingException(
                            context.path(), "the integer " + integer + " has no exact float value");
                }
                yield number;
            }
            default -> throw mismatch(value, context, "a float");
        };
    }

    /**
     * Converts a boolean.
     *
     * @param value the value
     * @param context the context of the value
     * @return the boolean
     * @throws TomlMappingException if {@code value} is not a boolean
     * @throws NullPointerException if an argument is null
     */
    public static boolean asBoolean(TomlValue value, Context context) {
        requireNonNull(value, "value");
        requireNonNull(context, "context");
        if (value instanceof TomlBoolean(var bool)) {
            return bool;
        }
        throw mismatch(value, context, "a boolean");
    }

    /**
     * Converts an offset date-time.
     *
     * @param value the value
     * @param context the context of the value
     * @return the date-time
     * @throws TomlMappingException if {@code value} is not an offset date-time
     * @throws NullPointerException if an argument is null
     */
    public static OffsetDateTime asOffsetDateTime(TomlValue value, Context context) {
        requireNonNull(value, "value");
        requireNonNull(context, "context");
        if (value instanceof TomlOffsetDateTime(var dateTime)) {
            return dateTime;
        }
        throw mismatch(value, context, "an offset date-time");
    }

    /**
     * Converts a local date-time.
     *
     * @param value the value
     * @param context the context of the value
     * @return the date-time
     * @throws TomlMappingException if {@code value} is not a local date-time
     * @throws NullPointerException if an argument is null
     */
    public static LocalDateTime asLocalDateTime(TomlValue value, Context context) {
        requireNonNull(value, "value");
        requireNonNull(context, "context");
        if (value instanceof TomlLocalDateTime(var dateTime)) {
            return dateTime;
        }
        throw mismatch(value, context, "a local date-time");
    }

    /**
     * Converts a local date.
     *
     * @param value the value
     * @param context the context of the value
     * @return the date
     * @throws TomlMappingException if {@code value} is not a local date
     * @throws NullPointerException if an argument is null
     */
    public static LocalDate asLocalDate(TomlValue value, Context context) {
        requireNonNull(value, "value");
        requireNonNull(context, "context");
        if (value instanceof TomlLocalDate(var date)) {
            return date;
        }
        throw mismatch(value, context, "a local date");
    }

    /**
     * Converts a local time.
     *
     * @param value the value
     * @param context the context of the value
     * @return the time
     * @throws TomlMappingException if {@code value} is not a local time
     * @throws NullPointerException if an argument is null
     */
    public static LocalTime asLocalTime(TomlValue value, Context context) {
        requireNonNull(value, "value");
        requireNonNull(context, "context");
        if (value instanceof TomlLocalTime(var time)) {
            return time;
        }
        throw mismatch(value, context, "a local time");
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
     * @param context the context of the value
     * @return the duration
     * @throws TomlMappingException if {@code value} is not a string, or not a duration
     * @throws NullPointerException if an argument is null
     */
    public static Duration asDuration(TomlValue value, Context context) {
        var string = asString(value, context);
        return duration(string)
                .orElseThrow(() -> new TomlMappingException(
                        context.path(),
                        "expected a duration, as \"PT1M30S\" or \"1m30s\", found " + Emitter.formatString(string)));
    }

    /**
     * Converts a string to a path of the default file system.
     *
     * @param value the value
     * @param context the context of the value
     * @return the path
     * @throws TomlMappingException if {@code value} is not a string, or not a path
     * @throws NullPointerException if an argument is null
     */
    public static Path asPath(TomlValue value, Context context) {
        var string = asString(value, context);
        try {
            return Path.of(string);
        } catch (InvalidPathException e) {
            throw new TomlMappingException(
                    context.path(), "invalid path " + Emitter.formatString(string) + ": " + e.getReason(), e);
        }
    }

    /**
     * Converts a string to a URI, as {@link URI#URI(String)} parses it.
     *
     * @param value the value
     * @param context the context of the value
     * @return the URI
     * @throws TomlMappingException if {@code value} is not a string, or not a URI
     * @throws NullPointerException if an argument is null
     */
    public static URI asUri(TomlValue value, Context context) {
        var string = asString(value, context);
        try {
            return new URI(string);
        } catch (URISyntaxException e) {
            throw new TomlMappingException(
                    context.path(), "invalid URI " + Emitter.formatString(string) + ": " + e.getReason(), e);
        }
    }

    /**
     * Puts the value of a record component that cannot be null into a table being built.
     *
     * @param <T> the type of the value
     * @param builder the builder of the table
     * @param key the key of the component
     * @param value the value of the component
     * @param converter converts {@code value} to a TOML value
     * @throws NullPointerException if an argument is null, or a value in {@code value}
     * @throws IllegalArgumentException if a string in {@code value} contains an unpaired surrogate
     */
    public static <T> void put(
            TomlTable.Builder builder, String key, T value, Function<? super T, ? extends TomlValue> converter) {
        requireNonNull(builder, "builder");
        requireNonNull(key, "key");
        requireNonNull(value, () -> "the value of " + Emitter.formatKey(key) + " is null");
        requireNonNull(converter, "converter");
        builder.put(key, converter.apply(value));
    }

    /**
     * Puts the value of a {@code @Nullable} record component into a table being built, unless it is null.
     *
     * @param <T> the type of the value
     * @param builder the builder of the table
     * @param key the key of the component
     * @param value the value of the component, or null to leave the key out
     * @param converter converts {@code value} to a TOML value
     * @throws NullPointerException if an argument but {@code value} is null, or a value in {@code value}
     * @throws IllegalArgumentException if a string in {@code value} contains an unpaired surrogate
     */
    public static <T> void putIfNotNull(
            TomlTable.Builder builder,
            String key,
            @Nullable T value,
            Function<? super T, ? extends TomlValue> converter) {
        requireNonNull(builder, "builder");
        requireNonNull(key, "key");
        requireNonNull(converter, "converter");
        if (value != null) {
            builder.put(key, converter.apply(value));
        }
    }

    /**
     * Converts a list or a set to an array, in its iteration order.
     *
     * @param <T> the type of the elements
     * @param values the elements
     * @param element converts each element
     * @return the array
     * @throws NullPointerException if an argument or an element is null
     */
    public static <T> TomlArray toArray(Collection<T> values, Function<? super T, ? extends TomlValue> element) {
        requireNonNull(values, "values");
        requireNonNull(element, "element");
        var array = new ArrayList<TomlValue>(values.size());
        for (var value : values) {
            array.add(element.apply(requireNonNull(value, "values cannot contain null")));
        }
        return new TomlArray(array);
    }

    /**
     * Converts a map to a table, in its iteration order.
     *
     * @param <T> the type of the values
     * @param values the map
     * @param entry converts the value of each key
     * @return the table
     * @throws NullPointerException if an argument, a key or a value is null
     * @throws IllegalArgumentException if a key contains an unpaired surrogate
     */
    public static <T> TomlTable toTable(Map<String, T> values, Function<? super T, ? extends TomlValue> entry) {
        requireNonNull(values, "values");
        requireNonNull(entry, "entry");
        var table = TomlTable.builder();
        values.forEach((key, value) -> table.put(
                requireNonNull(key, "values cannot contain a null key"),
                entry.apply(requireNonNull(value, () -> "the value of " + Emitter.formatKey(key) + " is null"))));
        return table.build();
    }

    private static long inRange(TomlValue value, Context context, long min, long max) {
        var integer = asLong(value, context);
        if (integer < min || integer > max) {
            throw new TomlMappingException(
                    context.path(), "expected an integer from " + min + " to " + max + ", found " + integer);
        }
        return integer;
    }

    private static List<TomlValue> array(TomlValue value, Context context) {
        requireNonNull(value, "value");
        requireNonNull(context, "context");
        if (value instanceof TomlArray(var values)) {
            return values;
        }
        throw mismatch(value, context, "an array");
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

    private static Context child(Context context, String key) {
        var path = context.path();
        return new Context((path.isEmpty() ? "" : path + ".") + Emitter.formatKey(key), context.unknownKeys());
    }

    private static Context index(Context context, int index) {
        return new Context(context.path() + "[" + index + "]", context.unknownKeys());
    }

    private static TomlMappingException mismatch(TomlValue value, Context context, String expected) {
        return new TomlMappingException(context.path(), "expected " + expected + ", found " + kind(value.getClass()));
    }

    private static String kind(Class<? extends TomlValue> type) {
        return KINDS.getOrDefault(type, "a value");
    }

    /**
     * Where a value is in the document, and how to bind it.
     *
     * @param path the path of the value, as {@link TomlMappingException#path()} describes it
     * @param unknownKeys what binding does with a key that no component takes
     * @since 0.1.0
     */
    public record Context(String path, UnknownKeys unknownKeys) {

        /**
         * Creates a context.
         *
         * @param path the path of the value
         * @param unknownKeys what binding does with a key that no component takes
         * @throws NullPointerException if {@code path} or {@code unknownKeys} is null
         */
        public Context {
            requireNonNull(path, "path");
            requireNonNull(unknownKeys, "unknownKeys");
        }
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
         * @param context the context of the value
         * @return the converted value
         * @throws TomlMappingException if {@code value} does not convert
         */
        T convert(TomlValue value, Context context);
    }
}
