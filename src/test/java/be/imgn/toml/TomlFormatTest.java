package be.imgn.toml;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class TomlFormatTest {

    @Test
    void formatsEmptyTable() {
        assertThat(Toml.format(TomlTable.builder().build())).isEmpty();
    }

    @Test
    void formatsScalars() {
        var table = TomlTable.builder()
                .put("string", new TomlString("text"))
                .put("integer", new TomlInteger(-42))
                .put("float", new TomlFloat(3.5))
                .put("large-float", new TomlFloat(1e300))
                .put("boolean", new TomlBoolean(false))
                .put(
                        "offset-date-time",
                        new TomlOffsetDateTime(OffsetDateTime.of(1979, 5, 27, 7, 32, 0, 0, ZoneOffset.ofHours(-8))))
                .put(
                        "utc-date-time",
                        new TomlOffsetDateTime(OffsetDateTime.of(1979, 5, 27, 7, 32, 0, 0, ZoneOffset.UTC)))
                .put("local-date-time", new TomlLocalDateTime(LocalDateTime.of(1979, 5, 27, 7, 32, 0, 120_000_000)))
                .put("local-date", new TomlLocalDate(LocalDate.of(1, 2, 3)))
                .put("local-time", new TomlLocalTime(LocalTime.of(7, 32)))
                .put("nanoseconds", new TomlLocalTime(LocalTime.of(7, 32, 1, 123_456_789)))
                .build();

        assertThat(Toml.format(table)).isEqualTo("""
                string = "text"
                integer = -42
                float = 3.5
                large-float = 1.0E300
                boolean = false
                offset-date-time = 1979-05-27T07:32:00-08:00
                utc-date-time = 1979-05-27T07:32:00Z
                local-date-time = 1979-05-27T07:32:00.12
                local-date = 0001-02-03
                local-time = 07:32:00
                nanoseconds = 07:32:01.123456789
                """);
    }

    @Test
    void formatsSpecialFloats() {
        var table = TomlTable.builder()
                .put("nan", new TomlFloat(Double.NaN))
                .put("inf", new TomlFloat(Double.POSITIVE_INFINITY))
                .put("minus-inf", new TomlFloat(Double.NEGATIVE_INFINITY))
                .put("minus-zero", new TomlFloat(-0.0))
                .build();

        assertThat(Toml.format(table)).isEqualTo("""
                nan = nan
                inf = inf
                minus-inf = -inf
                minus-zero = -0.0
                """);
    }

    @Test
    void escapesStrings() {
        var table = TomlTable.builder()
                .put("escaped", new TomlString("\"\\\b\t\n\f\r\u0000\u001B\u007F"))
                .put("unescaped", new TomlString("é😀'"))
                .build();

        assertThat(Toml.format(table)).isEqualTo("""
                escaped = "\\"\\\\\\b\\t\\n\\f\\r\\u0000\\u001B\\u007F"
                unescaped = "é😀'"
                """);
    }

    @Test
    void quotesKeysThatAreNotBare() {
        var table = TomlTable.builder()
                .put("bare_KEY-1", new TomlInteger(1))
                .put("", new TomlInteger(2))
                .put("a.b", new TomlInteger(3))
                .put("é", new TomlInteger(4))
                .put("with \"quotes\"", new TomlInteger(5))
                .build();

        assertThat(Toml.format(table)).isEqualTo("""
                bare_KEY-1 = 1
                "" = 2
                "a.b" = 3
                "é" = 4
                "with \\"quotes\\"" = 5
                """);
    }

    @Test
    void writesEntriesBeforeSubtables() {
        var table = TomlTable.builder()
                .put("first", new TomlInteger(1))
                .put("table", TomlTable.builder().put("key", new TomlInteger(2)).build())
                .put("last", new TomlInteger(3))
                .build();

        assertThat(Toml.format(table)).isEqualTo("""
                first = 1
                last = 3

                [table]
                key = 2
                """);
    }

    @Test
    void leavesOutHeadersOfTablesWithOnlySubtables() {
        var table = TomlTable.builder()
                .put(
                        "a",
                        TomlTable.builder()
                                .put(
                                        "b",
                                        TomlTable.builder()
                                                .put("c", new TomlInteger(1))
                                                .build())
                                .put("empty", TomlTable.builder().build())
                                .build())
                .build();

        assertThat(Toml.format(table)).isEqualTo("""
                [a.b]
                c = 1

                [a.empty]
                """);
    }

    @Test
    void writesArraysOfTablesWithDoubleBrackets() {
        var apple = TomlTable.builder()
                .put("name", new TomlString("apple"))
                .put(
                        "physical",
                        TomlTable.builder().put("color", new TomlString("red")).build())
                .put(
                        "varieties",
                        new TomlArray(List.of(
                                TomlTable.builder()
                                        .put("name", new TomlString("red delicious"))
                                        .build(),
                                TomlTable.builder()
                                        .put("name", new TomlString("granny smith"))
                                        .build())))
                .build();
        var banana = TomlTable.builder().put("name", new TomlString("banana")).build();
        var table = TomlTable.builder()
                .put("fruits", new TomlArray(List.of(apple, banana)))
                .build();

        assertThat(Toml.format(table)).isEqualTo("""
                [[fruits]]
                name = "apple"

                [fruits.physical]
                color = "red"

                [[fruits.varieties]]
                name = "red delicious"

                [[fruits.varieties]]
                name = "granny smith"

                [[fruits]]
                name = "banana"
                """);
    }

    @Test
    void writesOtherArraysInline() {
        var point = TomlTable.builder()
                .put("x", new TomlInteger(1))
                .put("y", TomlTable.builder().put("z", new TomlInteger(2)).build())
                .build();
        var table = TomlTable.builder()
                .put("empty", new TomlArray(List.of()))
                .put("nested", new TomlArray(List.of(new TomlArray(List.of(new TomlInteger(1))), new TomlString("a"))))
                .put("mixed", new TomlArray(List.of(point, new TomlInteger(3))))
                .put(
                        "empty-tables",
                        new TomlArray(List.of(
                                new TomlArray(List.of(TomlTable.builder().build())))))
                .build();

        assertThat(Toml.format(table)).isEqualTo("""
                empty = []
                nested = [[1], "a"]
                mixed = [{ x = 1, y = { z = 2 } }, 3]
                empty-tables = [[{}]]
                """);
    }
}
