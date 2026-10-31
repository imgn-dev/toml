package be.imgn.toml;

import static be.imgn.toml.Nulls.nullOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.entry;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.assertj.core.api.ThrowableAssertAlternative;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TomlBindingsTest {

    private static final TomlTable SERVER = Toml.parse("""
            host = "example.com"
            port = 8080
            "a.b" = true
            """).root();

    @Test
    void checksTableKeys() {
        assertThat(TomlBindings.table(SERVER, at("server"), "host", "port", "a.b", "other"))
                .isSameAs(SERVER);
    }

    @Test
    void rejectsUnknownKey() {
        assertThatMappingException(() -> TomlBindings.table(SERVER, at("server"), "host", "a.b"))
                .withMessage("server: unknown key port")
                .satisfies(e -> assertThat(e.path()).isEqualTo("server"));
        assertThatMappingException(() -> TomlBindings.table(SERVER, at(""), "port"))
                .withMessage("unknown keys host, \"a.b\"")
                .satisfies(e -> assertThat(e.path()).isEmpty());
    }

    @Test
    void ignoresUnknownKeysWhenTheContextDoes() {
        var ignoring = new TomlBindings.Context("server", UnknownKeys.IGNORE);

        assertThat(TomlBindings.table(SERVER, ignoring, "host")).isSameAs(SERVER);
    }

    @Test
    void passesTheModeToNestedValues() {
        var document = Toml.parse("servers = [{ host = \"a\", port = 80 }]").root();

        var hosts = TomlBindings.required(
                document,
                new TomlBindings.Context("", UnknownKeys.IGNORE),
                "servers",
                TomlBindings.list((value, context) -> TomlBindings.required(
                        TomlBindings.table(value, context, "host"), context, "host", TomlBindings::asString)));

        assertThat(hosts).containsExactly("a");
    }

    @Test
    void putsValues() {
        var builder = TomlTable.builder();

        TomlBindings.put(builder, "port", 8080, port -> new TomlInteger(port));
        TomlBindings.putIfNotNull(builder, "host", "example.com", TomlString::new);
        TomlBindings.putIfNotNull(builder, "timeout", null, TomlString::new);

        assertThat(builder.build())
                .isEqualTo(TomlTable.builder()
                        .put("port", 8080)
                        .put("host", "example.com")
                        .build());
    }

    @Test
    void rejectsNullValueToPut() {
        assertThatNullPointerException()
                .isThrownBy(() -> TomlBindings.put(TomlTable.builder(), "a.b", nullOf(String.class), TomlString::new))
                .withMessage("the value of \"a.b\" is null");
    }

    @Test
    void convertsCollectionToArray() {
        assertThat(TomlBindings.toArray(List.of(3, 1, 2), element -> new TomlInteger(element)))
                .isEqualTo(array(3, 1, 2));
        assertThatNullPointerException()
                .isThrownBy(
                        () -> TomlBindings.toArray(Collections.singletonList(nullOf(String.class)), TomlString::new))
                .withMessage("values cannot contain null");
    }

    @Test
    void convertsMapToTable() {
        var map = new LinkedHashMap<String, Integer>();
        map.put("b", 1);
        map.put("a", 2);

        var table = TomlBindings.toTable(map, value -> new TomlInteger(value));

        assertThat(table.entries()).containsExactly(entry("b", new TomlInteger(1)), entry("a", new TomlInteger(2)));
    }

    @Test
    void rejectsNullContext() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TomlBindings.Context(nullOf(String.class), UnknownKeys.REJECT))
                .withMessage("path");
        assertThatNullPointerException()
                .isThrownBy(() -> new TomlBindings.Context("", nullOf(UnknownKeys.class)))
                .withMessage("unknownKeys");
    }

    @Test
    void rejectsValueThatIsNotTable() {
        assertThatMappingException(() -> TomlBindings.table(new TomlInteger(1), at("server")))
                .withMessage("server: expected a table, found an integer");
    }

    @Test
    void convertsRequiredValue() {
        assertThat(TomlBindings.required(SERVER, at("server"), "port", TomlBindings::asInt))
                .isEqualTo(8080);
    }

    @Test
    void convertsValueAtItsPath() {
        assertThatMappingException(() -> TomlBindings.required(SERVER, at("server"), "port", TomlBindings::asString))
                .withMessage("server.port: expected a string, found an integer")
                .satisfies(e -> assertThat(e.path()).isEqualTo("server.port"));
        assertThatMappingException(() -> TomlBindings.required(SERVER, at(""), "a.b", TomlBindings::asString))
                .withMessage("\"a.b\": expected a string, found a boolean");
    }

    @Test
    void rejectsMissingRequiredValue() {
        assertThatMappingException(() -> TomlBindings.required(SERVER, at("server"), "timeout", TomlBindings::asString))
                .withMessage("server.timeout: missing");
    }

    @Test
    void convertsOptionalValue() {
        assertThat(TomlBindings.optional(SERVER, at("server"), "host", TomlBindings::asString))
                .isEqualTo("example.com");
        assertThat(TomlBindings.optional(SERVER, at("server"), "timeout", TomlBindings::asString))
                .isNull();
        assertThatMappingException(() -> TomlBindings.optional(SERVER, at("server"), "host", TomlBindings::asLong))
                .withMessage("server.host: expected an integer, found a string");
    }

    @Test
    void convertsString() {
        assertThat(TomlBindings.asString(new TomlString("text"), at("x"))).isEqualTo("text");
        assertThatMappingException(() -> TomlBindings.asString(new TomlBoolean(true), at("x")))
                .withMessage("x: expected a string, found a boolean");
    }

    @Test
    void convertsIntegersInRange() {
        assertThat(TomlBindings.asLong(new TomlInteger(Long.MIN_VALUE), at("x")))
                .isEqualTo(Long.MIN_VALUE);
        assertThat(TomlBindings.asInt(new TomlInteger(Integer.MAX_VALUE), at("x")))
                .isEqualTo(Integer.MAX_VALUE);
        assertThat(TomlBindings.asShort(new TomlInteger(Short.MIN_VALUE), at("x")))
                .isEqualTo(Short.MIN_VALUE);
        assertThat(TomlBindings.asByte(new TomlInteger(Byte.MAX_VALUE), at("x")))
                .isEqualTo(Byte.MAX_VALUE);
    }

    @Test
    void rejectsIntegersOutOfRange() {
        assertThatMappingException(() -> TomlBindings.asInt(new TomlInteger(Integer.MAX_VALUE + 1L), at("x")))
                .withMessage("x: expected an integer from -2147483648 to 2147483647, found 2147483648");
        assertThatMappingException(() -> TomlBindings.asShort(new TomlInteger(Short.MIN_VALUE - 1), at("x")))
                .withMessage("x: expected an integer from -32768 to 32767, found -32769");
        assertThatMappingException(() -> TomlBindings.asByte(new TomlInteger(128), at("x")))
                .withMessage("x: expected an integer from -128 to 127, found 128");
        assertThatMappingException(() -> TomlBindings.asLong(new TomlFloat(1), at("x")))
                .withMessage("x: expected an integer, found a float");
    }

    @Test
    void convertsDouble() {
        assertThat(TomlBindings.asDouble(new TomlFloat(2.5), at("x"))).isEqualTo(2.5);
        assertThat(TomlBindings.asDouble(new TomlFloat(Double.NaN), at("x"))).isNaN();
        assertThat(TomlBindings.asDouble(new TomlInteger(1L << 53), at("x"))).isEqualTo(0x1p53);
        assertThat(TomlBindings.asDouble(new TomlInteger(Long.MIN_VALUE), at("x")))
                .isEqualTo(-0x1p63);
    }

    @Test
    void rejectsIntegerWithoutExactDouble() {
        assertThatMappingException(() -> TomlBindings.asDouble(new TomlInteger((1L << 53) + 1), at("x")))
                .withMessage("x: the integer 9007199254740993 has no exact double value");
        assertThatMappingException(() -> TomlBindings.asDouble(new TomlInteger(Long.MAX_VALUE), at("x")))
                .withMessage("x: the integer 9223372036854775807 has no exact double value");
        assertThatMappingException(() -> TomlBindings.asDouble(new TomlString("1"), at("x")))
                .withMessage("x: expected a float, found a string");
    }

    @Test
    void convertsFloat() {
        assertThat(TomlBindings.asFloat(new TomlFloat(0.1), at("x"))).isEqualTo(0.1f);
        assertThat(TomlBindings.asFloat(new TomlFloat(Double.NEGATIVE_INFINITY), at("x")))
                .isEqualTo(Float.NEGATIVE_INFINITY);
        assertThat(TomlBindings.asFloat(new TomlInteger(1 << 24), at("x"))).isEqualTo(0x1p24f);
    }

    @Test
    void rejectsFloatOutOfRange() {
        assertThatMappingException(() -> TomlBindings.asFloat(new TomlFloat(1e39), at("x")))
                .withMessage("x: the float 1.0E39 is out of the range of float");
        assertThatMappingException(() -> TomlBindings.asFloat(new TomlInteger((1 << 24) + 1), at("x")))
                .withMessage("x: the integer 16777217 has no exact float value");
        assertThatMappingException(() -> TomlBindings.asFloat(new TomlInteger(Long.MAX_VALUE), at("x")))
                .withMessage("x: the integer 9223372036854775807 has no exact float value");
    }

    @Test
    void convertsBooleanAndDateTimes() {
        var offsetDateTime = OffsetDateTime.of(1979, 5, 27, 7, 32, 0, 0, ZoneOffset.UTC);
        var localDateTime = LocalDateTime.of(1979, 5, 27, 7, 32);
        var localDate = LocalDate.of(1979, 5, 27);
        var localTime = LocalTime.of(7, 32);
        assertThat(TomlBindings.asBoolean(new TomlBoolean(true), at("x"))).isTrue();
        assertThat(TomlBindings.asOffsetDateTime(new TomlOffsetDateTime(offsetDateTime), at("x")))
                .isEqualTo(offsetDateTime);
        assertThat(TomlBindings.asLocalDateTime(new TomlLocalDateTime(localDateTime), at("x")))
                .isEqualTo(localDateTime);
        assertThat(TomlBindings.asLocalDate(new TomlLocalDate(localDate), at("x")))
                .isEqualTo(localDate);
        assertThat(TomlBindings.asLocalTime(new TomlLocalTime(localTime), at("x")))
                .isEqualTo(localTime);
    }

    @Test
    void rejectsOtherKindOfDateTime() {
        var localDate = new TomlLocalDate(LocalDate.of(1979, 5, 27));
        assertThatMappingException(() -> TomlBindings.asBoolean(localDate, at("x")))
                .withMessage("x: expected a boolean, found a local date");
        assertThatMappingException(() -> TomlBindings.asOffsetDateTime(localDate, at("x")))
                .withMessage("x: expected an offset date-time, found a local date");
        assertThatMappingException(() -> TomlBindings.asLocalDateTime(localDate, at("x")))
                .withMessage("x: expected a local date-time, found a local date");
        assertThatMappingException(() -> TomlBindings.asLocalTime(localDate, at("x")))
                .withMessage("x: expected a local time, found a local date");
        assertThatMappingException(() -> TomlBindings.asLocalDate(new TomlLocalTime(LocalTime.NOON), at("x")))
                .withMessage("x: expected a local date, found a local time");
    }

    @Test
    void convertsIsoDuration() {
        assertThat(duration("PT1M30S")).isEqualTo(Duration.ofSeconds(90));
        assertThat(duration("-P2DT3H")).isEqualTo(Duration.ofHours(-51));
        assertThat(duration("+PT0.5S")).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void convertsDurationAmounts() {
        assertThat(duration("1h30m")).isEqualTo(Duration.ofMinutes(90));
        assertThat(duration("2.5s")).isEqualTo(Duration.ofMillis(2500));
        assertThat(duration("-500ms")).isEqualTo(Duration.ofMillis(-500));
        assertThat(duration("1d2h3m4s5ms6us7ns"))
                .isEqualTo(Duration.ofDays(1)
                        .plusHours(2)
                        .plusMinutes(3)
                        .plusSeconds(4)
                        .plusNanos(5_006_007));
        assertThat(duration("0.000000001s")).isEqualTo(Duration.ofNanos(1));
        assertThat(duration("-1.5us")).isEqualTo(Duration.ofNanos(-1500));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-", "10", "1x", "30m1h", "1h1h", "1.5ns", "1.s", ".5s", "1 h", "1h ", "PT", "pt1s"})
    void rejectsInvalidDuration(String string) {
        assertThatMappingException(() -> TomlBindings.asDuration(new TomlString(string), at("x")))
                .withMessage(
                        "x: expected a duration, as \"PT1M30S\" or \"1m30s\", found " + Emitter.formatString(string));
    }

    @Test
    void rejectsDurationOutOfRange() {
        assertThatMappingException(() -> TomlBindings.asDuration(new TomlString("999999999999999d"), at("x")))
                .withMessage("x: expected a duration, as \"PT1M30S\" or \"1m30s\", found \"999999999999999d\"");
        assertThatMappingException(() -> TomlBindings.asDuration(new TomlInteger(10), at("x")))
                .withMessage("x: expected a string, found an integer");
    }

    @Test
    void convertsPath() {
        assertThat(TomlBindings.asPath(new TomlString("dir/file.txt"), at("x"))).isEqualTo(Path.of("dir", "file.txt"));
        assertThatMappingException(() -> TomlBindings.asPath(new TomlString("a\0b"), at("x")))
                .withMessageStartingWith("x: invalid path \"a\\u0000b\": ");
    }

    @Test
    void convertsUri() {
        assertThat(TomlBindings.asUri(new TomlString("https://example.com/a?b"), at("x")))
                .isEqualTo(URI.create("https://example.com/a?b"));
        assertThatMappingException(() -> TomlBindings.asUri(new TomlString("https://exa mple.com"), at("x")))
                .withMessage("x: invalid URI \"https://exa mple.com\": Illegal character in authority");
    }

    @Test
    void checksTomlValueType() {
        var array = new TomlArray(List.of());
        assertThat(TomlBindings.as(TomlArray.class).convert(array, at("x"))).isSameAs(array);
        assertThat(TomlBindings.as(TomlValue.class).convert(array, at("x"))).isSameAs(array);
        assertThatMappingException(() -> TomlBindings.as(TomlTable.class).convert(array, at("x")))
                .withMessage("x: expected a table, found an array");
    }

    @Test
    void convertsList() {
        var list = TomlBindings.list(TomlBindings::asInt).convert(array(3, 1, 2), at("x"));
        assertThat(list).containsExactly(3, 1, 2);
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> list.add(4));
        assertThatMappingException(
                        () -> TomlBindings.list(TomlBindings::asString).convert(array(1), at("x")))
                .withMessage("x[0]: expected a string, found an integer");
        assertThatMappingException(() -> TomlBindings.list(TomlBindings::asInt).convert(new TomlInteger(1), at("x")))
                .withMessage("x: expected an array, found an integer");
    }

    @Test
    void convertsSet() {
        var set = TomlBindings.set(TomlBindings::asInt).convert(array(3, 1, 2), at("x"));
        assertThat(set).containsExactly(3, 1, 2);
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> set.add(4));
        assertThatMappingException(() -> TomlBindings.set(TomlBindings::asInt).convert(array(1, 2, 1), at("x")))
                .withMessage("x[2]: duplicate element");
    }

    @Test
    void convertsMap() {
        var map = TomlBindings.map(TomlBindings::asInt)
                .convert(Toml.parse("b = 1\na = 2").root(), at("x"));
        assertThat(map).containsExactly(entry("b", 1), entry("a", 2));
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> map.put("c", 3));
        assertThatMappingException(() -> TomlBindings.map(TomlBindings::asInt).convert(SERVER, at("x")))
                .withMessage("x.host: expected an integer, found a string");
        assertThatMappingException(() -> TomlBindings.map(TomlBindings::asInt).convert(array(), at("x")))
                .withMessage("x: expected a table, found an array");
    }

    @Test
    void nestsPaths() {
        var document = Toml.parse("servers = [{ ports = [80, \"443\"] }]").root();
        assertThatMappingException(() -> TomlBindings.required(
                        document,
                        at(""),
                        "servers",
                        TomlBindings.list((value, context) -> TomlBindings.required(
                                TomlBindings.table(value, context, "ports"),
                                context,
                                "ports",
                                TomlBindings.list(TomlBindings::asInt)))))
                .withMessage("servers[0].ports[1]: expected an integer, found a string")
                .satisfies(e -> assertThat(e.path()).isEqualTo("servers[0].ports[1]"));
    }

    @Test
    void reportsRejectedRecord() {
        var cause = new IllegalArgumentException("port must be positive");
        assertThat(TomlBindings.rejected(at("server"), cause))
                .hasMessage("server: port must be positive")
                .hasCause(cause);
        assertThat(TomlBindings.rejected(at(""), new IllegalStateException()))
                .hasMessage("java.lang.IllegalStateException");
    }

    @Test
    void reportsUnknownEnumString() {
        assertThat(TomlBindings.notOneOf(new TomlString("trace"), at("level"), "debug", "info"))
                .hasMessage("level: expected one of \"debug\", \"info\", found \"trace\"");
        assertThatMappingException(() -> TomlBindings.notOneOf(new TomlInteger(1), at("level"), "debug"))
                .withMessage("level: expected a string, found an integer");
    }

    @Test
    void rejectsNulls() {
        assertThatNullPointerException()
                .isThrownBy(() -> TomlBindings.asString(nullOf(TomlValue.class), at("x")))
                .withMessage("value");
        assertThatNullPointerException()
                .isThrownBy(() -> TomlBindings.asString(new TomlString(""), nullOf(TomlBindings.Context.class)))
                .withMessage("context");
        assertThatNullPointerException()
                .isThrownBy(
                        () -> TomlBindings.required(SERVER, at(""), "port", TomlBindingsTest.<String>nullConverter()))
                .withMessage("converter");
        assertThatNullPointerException()
                .isThrownBy(() -> TomlBindings.list(TomlBindingsTest.<String>nullConverter()))
                .withMessage("element");
    }

    @Test
    void rejectsDuplicateTableKeys() {
        assertThatIllegalArgumentException().isThrownBy(() -> TomlBindings.table(SERVER, at(""), "port", "port"));
    }

    @Test
    void rejectsRecordWithoutBinder() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Toml.parse("", Unbound.class))
                .withMessageStartingWith("no TomlBinder is registered for be.imgn.toml.TomlBindingsTest$Unbound: ");
    }

    private static TomlBindings.Context at(String path) {
        return new TomlBindings.Context(path, UnknownKeys.REJECT);
    }

    private static <T> TomlBindings.Converter<T> nullConverter() {
        return Collections.<TomlBindings.Converter<T>>singletonList(null).getFirst();
    }

    private static Duration duration(String string) {
        return TomlBindings.asDuration(new TomlString(string), at("x"));
    }

    private static TomlArray array(Object... values) {
        return new TomlArray(List.of(values).stream()
                .<TomlValue>map(value -> value instanceof Integer integer
                        ? new TomlInteger(integer)
                        : new TomlString(String.valueOf(value)))
                .toList());
    }

    private static ThrowableAssertAlternative<TomlMappingException> assertThatMappingException(
            ThrowingCallable callable) {
        return assertThatExceptionOfType(TomlMappingException.class).isThrownBy(callable);
    }

    private record Unbound(String name) {}
}
