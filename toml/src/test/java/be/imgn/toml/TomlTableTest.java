package be.imgn.toml;

import static be.imgn.toml.Nulls.nullOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.entry;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

class TomlTableTest {

    private static final TomlTable TABLE = Toml.parse("""
            string = "text"
            integer = 42
            float = 3.5
            boolean = true
            offset-date-time = 1979-05-27T07:32:00Z
            local-date-time = 1979-05-27T07:32:00
            local-date = 1979-05-27
            local-time = 07:32:00
            array = [1, 2]

            [server]
            port = 8080
            "host.name" = "example.com"
            """).root();

    @Test
    void getsValueOfKey() {
        assertThat(TABLE.get("integer")).contains(new TomlInteger(42));
    }

    @Test
    void getsValueOfDottedKey() {
        assertThat(TABLE.get("server.port")).contains(new TomlInteger(8080));
        assertThat(TABLE.get(" server . port ")).contains(new TomlInteger(8080));
    }

    @Test
    void getsValueOfQuotedKey() {
        assertThat(TABLE.get("server.\"host.name\"")).contains(new TomlString("example.com"));
        assertThat(TABLE.get("'server'.'host.name'")).contains(new TomlString("example.com"));
    }

    @Test
    void getsNothingForMissingKey() {
        assertThat(TABLE.get("missing")).isEmpty();
        assertThat(TABLE.get("server.missing")).isEmpty();
        assertThat(TABLE.get("integer.missing")).isEmpty();
    }

    @Test
    void rejectsInvalidKey() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TABLE.get("two words"))
                .withMessage(
                        "invalid key two words: line 1, column 5: expected '.' or the end of the key but found 'w'")
                .withCauseInstanceOf(TomlParseException.class);
        assertThatIllegalArgumentException().isThrownBy(() -> TABLE.get(""));
        assertThatIllegalArgumentException().isThrownBy(() -> TABLE.get("a."));
    }

    @Test
    void getsTypedValues() {
        assertThat(TABLE.getString("string")).isEqualTo("text");
        assertThat(TABLE.getLong("integer")).isEqualTo(42);
        assertThat(TABLE.getDouble("float")).isEqualTo(3.5);
        assertThat(TABLE.getBoolean("boolean")).isTrue();
        assertThat(TABLE.getOffsetDateTime("offset-date-time"))
                .isEqualTo(OffsetDateTime.of(1979, 5, 27, 7, 32, 0, 0, ZoneOffset.UTC));
        assertThat(TABLE.getLocalDateTime("local-date-time")).isEqualTo(LocalDateTime.of(1979, 5, 27, 7, 32));
        assertThat(TABLE.getLocalDate("local-date")).isEqualTo(LocalDate.of(1979, 5, 27));
        assertThat(TABLE.getLocalTime("local-time")).isEqualTo(LocalTime.of(7, 32));
        assertThat(TABLE.getArray("array").values()).containsExactly(new TomlInteger(1), new TomlInteger(2));
        assertThat(TABLE.getTable("server").getLong("port")).isEqualTo(8080);
        assertThat(TABLE.getInt("server.port")).isEqualTo(8080);
    }

    @Test
    void getsIntAtBoundsOfItsRange() {
        var table = Toml.parse("""
                min = -2147483648
                max = 2147483647
                """).root();

        assertThat(table.getInt("min")).isEqualTo(Integer.MIN_VALUE);
        assertThat(table.getInt("max")).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void intGetterRejectsIntegerOutOfRange() {
        var table = Toml.parse("""
                below = -2147483649
                above = 2147483648
                """).root();

        assertThatExceptionOfType(ArithmeticException.class)
                .isThrownBy(() -> table.getInt("below"))
                .withMessage("the value of the key below is -2147483649, out of the range of int");
        assertThatExceptionOfType(ArithmeticException.class)
                .isThrownBy(() -> table.getInt("above"))
                .withMessage("the value of the key above is 2147483648, out of the range of int");
    }

    @Test
    void intGetterRejectsOtherType() {
        assertThatExceptionOfType(ClassCastException.class)
                .isThrownBy(() -> TABLE.getInt("float"))
                .withMessage("the value of the key float is a TomlFloat, not a TomlInteger");
    }

    @Test
    void typedGetterRejectsMissingKey() {
        assertThatExceptionOfType(NoSuchElementException.class)
                .isThrownBy(() -> TABLE.getString("server.missing"))
                .withMessage("no value for the key server.missing");
    }

    @Test
    void typedGetterRejectsOtherType() {
        assertThatExceptionOfType(ClassCastException.class)
                .isThrownBy(() -> TABLE.getString("server.port"))
                .withMessage("the value of the key server.port is a TomlInteger, not a TomlString");
    }

    @Test
    void keepsOrderOfEntries() {
        var table = TomlTable.builder()
                .put("b", new TomlInteger(1))
                .put("a", new TomlInteger(2))
                .put("c", new TomlInteger(3))
                .put("a", new TomlInteger(4))
                .build();

        assertThat(table.entries().keySet()).containsExactly("b", "a", "c");
        assertThat(table.getLong("a")).isEqualTo(4);
    }

    @Test
    void equalsTableWithSameEntriesInOtherOrder() {
        var table = TomlTable.builder()
                .put("a", new TomlInteger(1))
                .put("b", new TomlInteger(2))
                .build();
        var reordered = TomlTable.builder()
                .put("b", new TomlInteger(2))
                .put("a", new TomlInteger(1))
                .build();

        assertThat(table).isEqualTo(reordered).hasSameHashCodeAs(reordered);
    }

    @Test
    void copiesEntries() {
        var entries = new LinkedHashMap<String, TomlValue>();
        entries.put("a", new TomlInteger(1));
        var table = new TomlTable(entries);

        entries.put("b", new TomlInteger(2));

        assertThat(table.entries()).containsOnlyKeys("a");
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> table.entries().put("c", new TomlInteger(3)));
    }

    @Test
    void builderKeepsWorkingAfterBuild() {
        var builder = TomlTable.builder().put("a", new TomlInteger(1));
        var table = builder.build();

        builder.put("b", new TomlInteger(2));

        assertThat(table.entries()).containsOnlyKeys("a");
        assertThat(builder.build().entries()).containsOnlyKeys("a", "b");
    }

    @Test
    void builderPutsScalars() {
        var offsetDateTime = OffsetDateTime.of(1979, 5, 27, 7, 32, 0, 0, ZoneOffset.UTC);
        var localDateTime = LocalDateTime.of(1979, 5, 27, 7, 32);
        var localDate = LocalDate.of(1979, 5, 27);
        var localTime = LocalTime.of(7, 32);

        var table = TomlTable.builder()
                .put("string", "text")
                .put("int", 8080)
                .put("long", 42L)
                .put("double", 3.5)
                .put("boolean", true)
                .put("offset-date-time", offsetDateTime)
                .put("local-date-time", localDateTime)
                .put("local-date", localDate)
                .put("local-time", localTime)
                .build();

        assertThat(table.entries())
                .containsExactly(
                        entry("string", new TomlString("text")),
                        entry("int", new TomlInteger(8080)),
                        entry("long", new TomlInteger(42)),
                        entry("double", new TomlFloat(3.5)),
                        entry("boolean", new TomlBoolean(true)),
                        entry("offset-date-time", new TomlOffsetDateTime(offsetDateTime)),
                        entry("local-date-time", new TomlLocalDateTime(localDateTime)),
                        entry("local-date", new TomlLocalDate(localDate)),
                        entry("local-time", new TomlLocalTime(localTime)));
    }

    @Test
    void builderRejectsInvalidScalarsWhenPut() {
        var builder = TomlTable.builder();

        assertThatNullPointerException()
                .isThrownBy(() -> builder.put("a", nullOf(String.class)))
                .withMessage("value");
        assertThatNullPointerException()
                .isThrownBy(() -> builder.put("a", nullOf(OffsetDateTime.class)))
                .withMessage("value");
        assertThatNullPointerException()
                .isThrownBy(() -> builder.put("a", nullOf(LocalDateTime.class)))
                .withMessage("value");
        assertThatNullPointerException()
                .isThrownBy(() -> builder.put("a", nullOf(LocalDate.class)))
                .withMessage("value");
        assertThatNullPointerException()
                .isThrownBy(() -> builder.put("a", nullOf(LocalTime.class)))
                .withMessage("value");
        assertThatNullPointerException()
                .isThrownBy(() -> builder.put(nullOf(String.class), 1))
                .withMessage("key");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder.put("a", "\uD800"))
                .withMessage("a string cannot contain the unpaired surrogate U+D800");
        assertThatIllegalArgumentException().isThrownBy(() -> builder.put("a", LocalDate.of(10_000, 1, 1)));
        assertThat(builder.build().entries()).isEmpty();
    }

    @Test
    void toBuilderStartsFromEntriesOfTable() {
        var table = TomlTable.builder().put("a", 1).put("b", 2).build();

        var changed = table.toBuilder().put("a", 3).put("c", 4).build();

        assertThat(changed.entries())
                .containsExactly(
                        entry("a", new TomlInteger(3)), entry("b", new TomlInteger(2)), entry("c", new TomlInteger(4)));
        assertThat(table.entries()).containsExactly(entry("a", new TomlInteger(1)), entry("b", new TomlInteger(2)));
    }

    @Test
    void rejectsNullArguments() {
        assertThatNullPointerException()
                .isThrownBy(() -> TABLE.get(nullOf(String.class)))
                .withMessage("key");
        assertThatNullPointerException()
                .isThrownBy(() -> TABLE.getLong(nullOf(String.class)))
                .withMessage("key");
        assertThatNullPointerException()
                .isThrownBy(() -> TABLE.getInt(nullOf(String.class)))
                .withMessage("key");
        assertThatNullPointerException()
                .isThrownBy(() -> new TomlTable(Nulls.<String, TomlValue>nullMap()))
                .withMessage("entries");
        assertThatNullPointerException()
                .isThrownBy(() -> new TomlTable(Collections.singletonMap(nullOf(String.class), new TomlInteger(1))))
                .withMessage("entries cannot contain a null key");
        assertThatNullPointerException()
                .isThrownBy(() -> new TomlTable(Collections.singletonMap("a", nullOf(TomlValue.class))))
                .withMessage("entries cannot contain a null value, as for the key a");
    }

    @Test
    void builderRejectsInvalidEntryWhenPut() {
        var builder = TomlTable.builder();

        assertThatNullPointerException()
                .isThrownBy(() -> builder.put(nullOf(String.class), new TomlInteger(1)))
                .withMessage("key");
        assertThatNullPointerException()
                .isThrownBy(() -> builder.put("a", nullOf(TomlValue.class)))
                .withMessage("value");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder.put("\uD800", new TomlInteger(1)))
                .withMessage("a key cannot contain the unpaired surrogate U+D800");
    }

    @Test
    void rejectsKeyWithUnpairedSurrogate() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TomlTable(Map.of("\uD800", new TomlInteger(1))))
                .withMessage("a key cannot contain the unpaired surrogate U+D800");
    }
}
