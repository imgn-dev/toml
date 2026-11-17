package be.imgn.toml;

import static be.imgn.toml.Nulls.nullOf;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assumptions.assumeThat;

import be.imgn.gaia.core.io.ByteSink;
import be.imgn.gaia.core.io.ByteSource;
import be.imgn.gaia.core.io.CharSink;
import be.imgn.gaia.core.io.CharSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TomlTest {

    private static final String DOCUMENT = """
            title = "TOML"

            [owner]
            name = "Tom"
            """;

    private static final TomlTable TABLE = TomlTable.builder()
            .put("title", new TomlString("TOML"))
            .put("owner", TomlTable.builder().put("name", new TomlString("Tom")).build())
            .build();

    @TempDir
    Path directory;

    @Test
    void parsesEveryType() {
        var table = Toml.parse("""
                string = "text"
                integer = 42
                float = 3.5
                boolean = true
                offset-date-time = 1979-05-27T07:32:00.5-08:00
                local-date-time = 1979-05-27T07:32:00
                local-date = 1979-05-27
                local-time = 07:32
                array = [1, "two"]
                table = { key = "value" }
                """).root();

        assertThat(table.entries())
                .containsExactly(
                        entry("string", new TomlString("text")),
                        entry("integer", new TomlInteger(42)),
                        entry("float", new TomlFloat(3.5)),
                        entry("boolean", new TomlBoolean(true)),
                        entry(
                                "offset-date-time",
                                new TomlOffsetDateTime(
                                        OffsetDateTime.of(1979, 5, 27, 7, 32, 0, 500_000_000, ZoneOffset.ofHours(-8)))),
                        entry("local-date-time", new TomlLocalDateTime(LocalDateTime.of(1979, 5, 27, 7, 32))),
                        entry("local-date", new TomlLocalDate(LocalDate.of(1979, 5, 27))),
                        entry("local-time", new TomlLocalTime(LocalTime.of(7, 32))),
                        entry("array", new TomlArray(List.of(new TomlInteger(1), new TomlString("two")))),
                        entry(
                                "table",
                                TomlTable.builder()
                                        .put("key", new TomlString("value"))
                                        .build()));
    }

    @Test
    void parsesCharSource() throws IOException {
        assertThat(Toml.parse(CharSource.wrap(DOCUMENT)).root()).isEqualTo(TABLE);
    }

    @Test
    void parsesUtf8ByteSource() throws IOException {
        var source = ByteSource.wrap("key = \"é\"".getBytes(UTF_8));

        assertThat(Toml.parse(source).root().getString("key")).isEqualTo("é");
    }

    @Test
    void parsesUtf8File() throws IOException {
        var file = Files.write(directory.resolve("config.toml"), "key = \"é\"".getBytes(UTF_8));

        assertThat(Toml.parse(file).root().getString("key")).isEqualTo("é");
    }

    @Test
    void rejectsFileThatIsNotUtf8() throws IOException {
        var file = Files.write(directory.resolve("config.toml"), "key = \"é\"".getBytes(ISO_8859_1));

        assertThatExceptionOfType(TomlParseException.class)
                .isThrownBy(() -> Toml.parse(file))
                .withMessage("line 1, column 8: invalid UTF-8");
    }

    @Test
    void propagatesFailureToReadFile() {
        assertThatExceptionOfType(NoSuchFileException.class)
                .isThrownBy(() -> Toml.parse(directory.resolve("missing.toml")));
    }

    @Test
    void ignoresByteOrderMark() {
        assertThat(Toml.parse("﻿" + DOCUMENT).root()).isEqualTo(TABLE);
    }

    @Test
    void parsesUtf8ByteSourceHoldingTheReplacementCharacter() throws IOException {
        var source = ByteSource.wrap("x = \"�\"".getBytes(UTF_8));

        assertThat(Toml.parse(source).root().getString("x")).isEqualTo("�");
    }

    @Test
    void rejectsInvalidUtf8WithItsPosition() {
        var source = ByteSource.wrap(new byte[] {'a', '=', '1', '\n', 'b', '=', '"', (byte) 0xFF, '"'});

        assertThatExceptionOfType(TomlParseException.class)
                .isThrownBy(() -> Toml.parse(source))
                .withMessage("line 2, column 4: invalid UTF-8")
                .satisfies(e -> assertThat(e.position()).isEqualTo(new TomlPosition(2, 4)));
    }

    @Test
    void reportsWhereAndWhatTheErrorIs() {
        assertThatExceptionOfType(TomlParseException.class)
                .isThrownBy(() -> Toml.parse("a = 1\nb = [1 2]"))
                .withMessage("line 2, column 8: expected ',' or ']' but found '2'")
                .satisfies(e -> assertThat(e.position()).isEqualTo(new TomlPosition(2, 8)));
    }

    @Test
    void describesWhatWasFound() {
        assertParseError("x = ", "line 1, column 5: expected a value but found end of input");
        assertParseError("%bad = 1", "line 1, column 1: expected a key but found '%'");
        assertParseError("x = 0123", "line 1, column 5: a number cannot have leading zeros");
        assertParseError("x = \"a\u0001\"", "line 1, column 7: the control character U+0001 is not allowed here");
        assertParseError("x = 1\ry = 2", "line 1, column 7: a carriage return must be followed by a line feed");
        assertParseError("x = 1 y = 2", "line 1, column 7: expected a newline after the statement but found 'y'");
    }

    @Test
    void parsesNumbersWithUnderscoresAndSigns() {
        var root = Toml.parse("""
                a = -9_223_372_036_854_775_808
                b = +1_000
                c = 1_0.2_5e+1_0
                d = 0xdead_BEEF
                e = -0.0
                f = 00:00:00.1234567899
                """).root();

        assertThat(root.getLong("a")).isEqualTo(Long.MIN_VALUE);
        assertThat(root.getLong("b")).isEqualTo(1000);
        assertThat(root.getDouble("c")).isEqualTo(10.25e10);
        assertThat(root.getLong("d")).isEqualTo(0xdeadbeefL);
        assertThat(root.getDouble("e")).isEqualTo(-0.0);
        assertThat(root.get("f")).contains(new TomlLocalTime(LocalTime.of(0, 0, 0, 123_456_789)));
    }

    @Test
    void rejectsNumbersOutOfRangeOrWithLeadingZeros() {
        assertParseError(
                "x = +9_223_372_036_854_775_808",
                "line 1, column 5: the integer 9223372036854775808 does not fit in 64 bits");
        assertParseError("x = 0_1", "line 1, column 5: a number cannot have leading zeros");
        assertParseError("x = 1__0", "line 1, column 7: an underscore must be between two digits");
        assertParseError("x = 1.5_", "line 1, column 9: an underscore must be between two digits");
    }

    @Test
    void parsesDecimalIntegersOfEveryLength() {
        var integers = new ArrayList<String>();
        for (var digits = 1; digits <= 19; digits++) {
            // 19 nines are past the range of a long, but not an 8 followed by 18 nines.
            var nines = digits == 19 ? "8" + "9".repeat(18) : "9".repeat(digits);
            var one = "1" + "0".repeat(digits - 1);
            for (var integer : List.of(nines, one)) {
                integers.add(integer);
                integers.add("+" + integer);
                integers.add("-" + integer);
            }
        }
        integers.addAll(
                List.of("0", "+0", "-0", "9223372036854775807", "-9223372036854775808", "+9223372036854775807"));
        var random = new SplittableRandom(42);
        for (var i = 0; i < 10_000; i++) {
            integers.add(Long.toString(random.nextLong() >> random.nextInt(64)));
        }

        for (var integer : integers) {
            var expected = Long.parseLong(integer);
            assertThat(Toml.parse("x = " + integer).root().getLong("x"))
                    .as(integer)
                    .isEqualTo(expected);
            var grouped = integer.replaceAll("(\\d)(?=(\\d{3})+$)", "$1_");
            assertThat(Toml.parse("x = " + grouped).root().getLong("x"))
                    .as(grouped)
                    .isEqualTo(expected);
        }
    }

    @Test
    void rejectsDecimalIntegersPastLongRange() {
        assertParseError(
                "x = 9223372036854775808", "line 1, column 5: the integer 9223372036854775808 does not fit in 64 bits");
        assertParseError(
                "x = -9223372036854775809",
                "line 1, column 5: the integer -9223372036854775809 does not fit in 64 bits");
        assertParseError(
                "x = 10000000000000000000",
                "line 1, column 5: the integer 10000000000000000000 does not fit in 64 bits");
        assertParseError(
                "x = -99999999999999999999",
                "line 1, column 5: the integer -99999999999999999999 does not fit in 64 bits");
        assertParseError("x = 00", "line 1, column 5: a number cannot have leading zeros");
        assertParseError("x = -01", "line 1, column 5: a number cannot have leading zeros");
        assertParseError("x = +", "line 1, column 6: expected a number but found end of input");
    }

    @Test
    void rejectsUnpairedSurrogates() {
        var message = "the unpaired surrogate U+%04X is not allowed";
        assertParseError("x = \"a\uD800b\"", "line 1, column 7: " + message.formatted(0xD800));
        assertParseError("x = 'a\uDC00'", "line 1, column 7: " + message.formatted(0xDC00));
        assertParseError("x = \"\"\"\na\uD800\"\"\"", "line 2, column 2: " + message.formatted(0xD800));
        assertParseError("x = '''\na\uDC00'''", "line 2, column 2: " + message.formatted(0xDC00));
        assertParseError("\"a\uD800\" = 1", "line 1, column 3: " + message.formatted(0xD800));
        assertParseError("x = {'\uDC00' = 1}", "line 1, column 7: " + message.formatted(0xDC00));
        assertParseError("x = 1 # a\uD800", "line 1, column 10: " + message.formatted(0xD800));
        assertParseError("# \uD800𐀀", "line 1, column 3: " + message.formatted(0xD800));
        assertParseError("# \uDC00\uD800", "line 1, column 3: " + message.formatted(0xDC00));
    }

    @Test
    void acceptsSurrogatePairs() {
        var document = Toml.parse("\"😀\" = \"😀\" # 😀\ny = '''😀'''");

        assertThat(document.root().entries()).containsEntry("😀", new TomlString("😀"));
        assertThat(document.root().getString("y")).isEqualTo("😀");
    }

    @Test
    void positionsSemanticErrorsAtTheKeyOrHeader() {
        assertParseError("a = 1\na = 2", "line 2, column 1: duplicate key 'a'");
        assertParseError("x = {a = 1, a = 2}", "line 1, column 13: duplicate key 'a'");
        assertParseError("[a]\n[a]", "line 2, column 1: the table 'a' is already defined");
        assertParseError("[[a]]\n [a]", "line 2, column 2: the table 'a' is already defined");
        assertParseError("[a]\n[[a]]", "line 2, column 1: cannot define 'a' again as an array of tables");
        assertParseError("a = 1\n[ a.b ]", "line 2, column 3: the key 'a' is not a table");
        assertParseError("a = {b = 1}\na.c = 2", "line 2, column 1: cannot extend the inline table 'a'");
        assertParseError(
                "[a.b]\nc = 1\n[a]\nx = 1\nb.d = 1", "line 5, column 1: cannot extend the table 'b' with a dotted key");
    }

    @Test
    void positionsValueErrorsAtTheValue() {
        assertParseError(
                "x = 99999999999999999999",
                "line 1, column 5: the integer 99999999999999999999 does not fit in 64 bits");
        assertParseError(
                "x = 0x1_0000_0000_0000_0000",
                "line 1, column 5: the integer 0x10000000000000000 does not fit in 64 bits");
        assertParseError("x = [1, 0123]", "line 1, column 9: a number cannot have leading zeros");
        assertParseError("x = +0x1", "line 1, column 5: a hexadecimal, octal or binary integer cannot have a sign");
        assertParseErrorAt("d = 2026-02-30", new TomlPosition(1, 5));
        assertParseErrorAt("d = 2026-02-01T25:00:00", new TomlPosition(1, 5));
        assertParseErrorAt("d = 2026-02-01T00:00:00+19:00", new TomlPosition(1, 5));
        assertParseErrorAt("t = 24:00:00", new TomlPosition(1, 5));
    }

    @Test
    void rejectsNonAsciiDigits() {
        // U+0661 and U+FF11 are digits for Character.digit, but not for TOML.
        assertParseError("x = 0x\u0661", "line 1, column 7: expected a digit in base 16 but found '\u0661'");
        assertParseError(
                "x = 0b1\uFF11", "line 1, column 8: expected a newline after the statement but found '\uFF11'");
    }

    @Test
    void wrapsTheCauseOfAnInvalidValue() {
        assertThatExceptionOfType(TomlParseException.class)
                .isThrownBy(() -> Toml.parse("x = 9223372036854775808"))
                .withMessage("line 1, column 5: the integer 9223372036854775808 does not fit in 64 bits")
                .withCauseInstanceOf(NumberFormatException.class);
    }

    @Test
    void formatsTable() {
        assertThat(Toml.format(TABLE)).isEqualTo(DOCUMENT);
    }

    @Test
    void writesToCharSink() throws IOException {
        var writer = new StringWriter();
        CharSink sink = () -> writer;

        Toml.write(TABLE, sink);

        assertThat(writer).hasToString(DOCUMENT);
    }

    @Test
    void closesStreamOfCharSink() throws IOException {
        var closed = new AtomicBoolean();
        CharSink sink = () -> new StringWriter() {
            @Override
            public void close() {
                closed.set(true);
            }
        };

        Toml.write(TABLE, sink);

        assertThat(closed).isTrue();
    }

    @Test
    void propagatesFailureOfCharSink() {
        var failure = new IOException("disk full");
        CharSink sink = () -> new Writer() {
            @Override
            public void write(char[] buffer, int offset, int length) throws IOException {
                throw failure;
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };

        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> Toml.write(TABLE, sink))
                .isSameAs(failure);
    }

    @Test
    void writesUtf8ToByteSink() throws IOException {
        var out = new ByteArrayOutputStream();
        ByteSink sink = () -> out;

        Toml.write(TomlTable.builder().put("key", new TomlString("é")).build(), sink);

        assertThat(out.toByteArray()).isEqualTo("key = \"é\"\n".getBytes(UTF_8));
    }

    @Test
    void writesFileAtomically() throws IOException {
        var file = directory.resolve("config.toml");

        Toml.writeAtomically(TomlTable.builder().put("key", new TomlString("é")).build(), file);

        assertThat(Files.readAllBytes(file)).isEqualTo("key = \"é\"\n".getBytes(UTF_8));
    }

    @Test
    void replacesFileAtomically() throws IOException {
        var file = Files.writeString(directory.resolve("config.toml"), "old = true\n");

        Toml.writeAtomically(TABLE, file);

        assertThat(file).hasContent(DOCUMENT);
    }

    @Test
    void keepsFileWhenAtomicWriteFails() throws IOException {
        assumeThat(FileSystems.getDefault().supportedFileAttributeViews()).contains("posix");
        var file = Files.writeString(directory.resolve("config.toml"), "old = true\n");
        // The new content goes to a temporary file in the same directory first, which a read-only one refuses.
        var permissions = Files.getPosixFilePermissions(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"));
        try {
            assertThatExceptionOfType(IOException.class).isThrownBy(() -> Toml.writeAtomically(TABLE, file));
        } finally {
            Files.setPosixFilePermissions(directory, permissions);
        }

        assertThat(file).hasContent("old = true\n");
    }

    @Test
    void rejectsNullArguments() {
        var table = TomlTable.builder().build();
        var file = directory.resolve("config.toml");
        CharSink charSink = StringWriter::new;
        ByteSink byteSink = ByteArrayOutputStream::new;

        assertThatNullPointerException()
                .isThrownBy(() -> Toml.parse(nullOf(String.class)))
                .withMessage("document");
        assertThatNullPointerException()
                .isThrownBy(() -> Toml.parse(nullOf(CharSource.class)))
                .withMessage("source");
        assertThatNullPointerException()
                .isThrownBy(() -> Toml.parse(nullOf(ByteSource.class)))
                .withMessage("source");
        assertThatNullPointerException()
                .isThrownBy(() -> Toml.parse(nullOf(Path.class)))
                .withMessage("file");
        assertThatNullPointerException()
                .isThrownBy(() -> Toml.parse(nullOf(Path.class), Object.class))
                .withMessage("file");
        assertThatNullPointerException()
                .isThrownBy(() -> Toml.parse(file, Nulls.<Object>nullClass()))
                .withMessage("type");
        assertThatNullPointerException()
                .isThrownBy(() -> Toml.format(nullOf(TomlTable.class)))
                .withMessage("table");
        assertThatNullPointerException()
                .isThrownBy(() -> Toml.write(nullOf(TomlTable.class), charSink))
                .withMessage("table");
        assertThatNullPointerException()
                .isThrownBy(() -> Toml.write(table, nullOf(CharSink.class)))
                .withMessage("sink");
        assertThatNullPointerException()
                .isThrownBy(() -> Toml.write(nullOf(TomlTable.class), byteSink))
                .withMessage("table");
        assertThatNullPointerException()
                .isThrownBy(() -> Toml.write(table, nullOf(ByteSink.class)))
                .withMessage("sink");
        assertThatNullPointerException()
                .isThrownBy(() -> Toml.writeAtomically(nullOf(TomlTable.class), file))
                .withMessage("table");
        assertThatNullPointerException()
                .isThrownBy(() -> Toml.writeAtomically(table, nullOf(Path.class)))
                .withMessage("file");
    }

    private static void assertParseError(String document, String message) {
        assertThatExceptionOfType(TomlParseException.class)
                .isThrownBy(() -> Toml.parse(document))
                .withMessage(message);
    }

    private static void assertParseErrorAt(String document, TomlPosition position) {
        assertThatExceptionOfType(TomlParseException.class)
                .isThrownBy(() -> Toml.parse(document))
                .satisfies(e -> assertThat(e.position()).isEqualTo(position));
    }
}
