package be.imgn.toml;

import static be.imgn.gaia.core.base.Throwables.illegalArgument;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/// A recursive-descent TOML 1.1 parser.
///
/// The tables a document defines with headers and dotted keys are built as a mutable tree, which records how
/// each table was defined so that the rules against redefining a table can be enforced, and which becomes
/// [TomlTable]s at the end. Arrays and inline tables cannot be extended once closed, so they become values
/// right away.
///
/// The tree also records where each key and value is written, and so do the shapes it keeps of the arrays and
/// inline tables it reads: the tree and those shapes become the [TomlPositions] of the document.
final class Parser {

    private static final char BYTE_ORDER_MARK = '﻿';
    private static final int DECIMAL_RADIX = 10;
    private static final int HEXADECIMAL_RADIX = 16;
    private static final int OCTAL_RADIX = 8;
    private static final int BINARY_RADIX = 2;
    private static final int YEAR_DIGITS = 4;
    private static final int NANOSECOND_DIGITS = 9;
    private static final int BYTE_ESCAPE_DIGITS = 2;
    private static final int SHORT_UNICODE_ESCAPE_DIGITS = 4;
    private static final int LONG_UNICODE_ESCAPE_DIGITS = 8;
    private static final int MULTILINE_DELIMITER_LENGTH = 3;
    private static final int MAX_CLOSING_QUOTES = 5;
    // An escape character's position in ESCAPES is its replacement's position in ESCAPED.
    private static final String ESCAPES = "\"\\btnfre";
    private static final String ESCAPED = "\"\\\b\t\n\f\r\u001B";
    private static final int INITIAL_POSITIONS = 8;

    private final Cursor in;
    private final TableNode root = new TableNode(Kind.EXPLICIT);
    private final List<TableNode> openDottedTables = new ArrayList<>();
    private TableNode current = root;
    // The shape of the array or inline table that value() last read, or null if it read another value.
    private TomlPositions.@Nullable Shape valueShape;

    private Parser(String text) {
        in = new Cursor(text);
    }

    static TomlDocument parse(String document) {
        var text = !document.isEmpty() && document.charAt(0) == BYTE_ORDER_MARK ? document.substring(1) : document;
        return new Parser(text).document();
    }

    /// Parses a dotted key as written in a document, such as `a."b.c"`, into its parts.
    static List<String> parseKey(String key) {
        var parser = new Parser(key);
        try {
            var keys = parser.keyPath();
            if (!parser.in.atEnd()) {
                throw parser.in.expected("'.' or the end of the key");
            }
            return keys.stream().map(Key::name).toList();
        } catch (TomlParseException e) {
            throw illegalArgument(e, "invalid key %s: %s", key, e.getMessage());
        }
    }

    private TomlDocument document() {
        skipBlank();
        while (!in.atEnd()) {
            if (in.isAt('[')) {
                header();
            } else {
                keyValue(current);
            }
            statementEnd();
            skipBlank();
        }
        var table = freeze(root);
        return new TomlDocument(table, new TomlPositions(table, root));
    }

    // Tables and keys

    private void header() {
        var start = in.here();
        in.expect('[');
        var arrayOfTables = in.match('[');
        var keys = keyPath();
        in.expect(']');
        if (arrayOfTables) {
            in.expect(']');
        }
        for (var table : openDottedTables) {
            table.closed = true;
        }
        openDottedTables.clear();
        var parent = parentOf(keys);
        var last = keys.getLast();
        current = arrayOfTables ? appendTable(parent, last, start) : defineTable(parent, last, start);
    }

    private TableNode parentOf(List<Key> keys) {
        var table = root;
        for (var i = 0; i < keys.size() - 1; i++) {
            var key = keys.get(i);
            var existing = table.entries.get(key.name());
            table = switch (existing) {
                case null -> table.add(key.name(), new TableNode(Kind.IMPLICIT, key.position(), key.position()));
                case TableNode node -> node;
                case ArrayOfTables array -> array.elements().getLast();
                case Leaf leaf -> throw notATable(key, leaf);
            };
        }
        return table;
    }

    private TableNode appendTable(TableNode parent, Key key, long header) {
        var array = switch (parent.entries.get(key.name())) {
            case null ->
                parent.add(key.name(), new ArrayOfTables(new ArrayList<>(), new Positions(), key.position(), header));
            case ArrayOfTables existing -> existing;
            default -> throw Cursor.error(header, "cannot define '" + key.name() + "' again as an array of tables");
        };
        var element = new TableNode(Kind.EXPLICIT);
        array.elements().add(element);
        array.positions().add(header);
        return element;
    }

    private TableNode defineTable(TableNode parent, Key key, long header) {
        var existing = parent.entries.get(key.name());
        if (existing == null) {
            return parent.add(key.name(), new TableNode(Kind.EXPLICIT, key.position(), header));
        }
        if (existing instanceof TableNode table && table.kind == Kind.IMPLICIT) {
            table.kind = Kind.EXPLICIT;
            // The header defines the table, where it only appeared as a parent before.
            table.keyPosition = key.position();
            table.valuePosition = header;
            return table;
        }
        throw Cursor.error(header, "the table '" + key.name() + "' is already defined");
    }

    private void keyValue(TableNode base) {
        var keys = keyPath();
        in.expect('=');
        skipWhitespace();
        var start = in.here();
        var value = value();
        var shape = valueShape;
        var table = base;
        for (var i = 0; i < keys.size() - 1; i++) {
            var key = keys.get(i);
            var existing = table.entries.get(key.name());
            table = switch (existing) {
                case null -> openDottedTable(table, key);
                case TableNode node when node.kind != Kind.EXPLICIT && !node.closed -> node;
                case TableNode node ->
                    throw Cursor.error(
                            key.position(), "cannot extend the table '" + key.name() + "' with a dotted key");
                case ArrayOfTables array -> throw notATable(key, array);
                case Leaf leaf -> throw notATable(key, leaf);
            };
        }
        var last = keys.getLast();
        if (table.entries.containsKey(last.name())) {
            throw Cursor.error(last.position(), "duplicate key '" + last.name() + "'");
        }
        table.add(last.name(), new Leaf(value, last.position(), start, shape));
    }

    private TableNode openDottedTable(TableNode parent, Key key) {
        var table = parent.add(key.name(), new TableNode(Kind.DOTTED, key.position(), key.position()));
        openDottedTables.add(table);
        return table;
    }

    private static TomlParseException notATable(Key key, Node node) {
        return node instanceof Leaf leaf && leaf.value() instanceof TomlTable
                ? Cursor.error(key.position(), "cannot extend the inline table '" + key.name() + "'")
                : Cursor.error(key.position(), "the key '" + key.name() + "' is not a table");
    }

    private List<Key> keyPath() {
        var keys = new ArrayList<Key>();
        do {
            skipWhitespace();
            var position = in.here();
            keys.add(new Key(key(), position));
            skipWhitespace();
        } while (in.match('.'));
        return keys;
    }

    private String key() {
        if (in.isAt('"') || in.isAt('\'')) {
            return string(false);
        }
        if (in.atEnd() || !Cursor.isBareKeyChar(in.peek())) {
            throw in.expected("a key");
        }
        var start = in.position();
        return in.text(start, in.skipBareKey());
    }

    // Values

    private TomlValue value() {
        if (in.atEnd()) {
            throw in.expected("a value");
        }
        valueShape = null;
        return switch (in.peek()) {
            case '"', '\'' -> new TomlString(string(true));
            case '[' -> array();
            case '{' -> inlineTable();
            case 't', 'f' -> bool();
            default -> numberOrDateTime();
        };
    }

    private TomlValue numberOrDateTime() {
        if (in.areDigits(YEAR_DIGITS) && in.isAt(YEAR_DIGITS, '-')) {
            return dateTime();
        }
        if (in.areDigits(2) && in.isAt(2, ':')) {
            return localTime();
        }
        return number();
    }

    private TomlValue bool() {
        var value = in.isAt('t');
        in.expectWord(value ? "true" : "false");
        return new TomlBoolean(value);
    }

    private TomlValue array() {
        in.expect('[');
        var values = new ArrayList<TomlValue>();
        var shape = new InlineArray();
        skipBlank();
        while (!in.match(']')) {
            var position = in.here();
            values.add(value());
            shape.add(position, valueShape);
            skipBlank();
            if (!in.match(',') && !in.isAt(']')) {
                throw in.expected("',' or ']'");
            }
            skipBlank();
        }
        valueShape = shape;
        return new TomlArray(values);
    }

    private TomlValue inlineTable() {
        in.expect('{');
        var table = new TableNode(Kind.EXPLICIT);
        skipBlank();
        while (!in.match('}')) {
            keyValue(table);
            skipBlank();
            if (!in.match(',') && !in.isAt('}')) {
                throw in.expected("',' or '}'");
            }
            skipBlank();
        }
        valueShape = table;
        return freeze(table);
    }

    // Numbers

    private TomlValue number() {
        var start = in.here();
        var from = in.position();
        var negative = in.isAt('-');
        var signed = in.match('-') || in.match('+');
        if (in.isAt('i') || in.isAt('n')) {
            return infinityOrNan(negative);
        }
        if (!in.isDigitAt(0)) {
            throw in.expected(signed ? "a number" : "a value");
        }
        if (in.isAt('0') && (in.isAt(1, 'x') || in.isAt(1, 'o') || in.isAt(1, 'b'))) {
            if (signed) {
                throw Cursor.error(start, "a hexadecimal, octal or binary integer cannot have a sign");
            }
            return radixInteger(start);
        }
        return decimal(start, from);
    }

    private TomlValue infinityOrNan(boolean negative) {
        if (in.isAt('i')) {
            in.expectWord("inf");
            return new TomlFloat(negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY);
        }
        in.expectWord("nan");
        return new TomlFloat(Double.NaN);
    }

    private TomlValue radixInteger(long start) {
        in.skip(1);
        var prefix = in.advance();
        var radix = switch (prefix) {
            case 'x' -> HEXADECIMAL_RADIX;
            case 'o' -> OCTAL_RADIX;
            default -> BINARY_RADIX;
        };
        var from = in.position();
        var underscores = digits(radix, "a digit in base " + radix);
        var to = in.position();
        try {
            return new TomlInteger(
                    underscores ? Long.parseLong(withoutUnderscores(from, to), radix) : in.parseLong(from, to, radix));
        } catch (NumberFormatException e) {
            throw Cursor.error(
                    start, "the integer 0" + prefix + withoutUnderscores(from, to) + " does not fit in 64 bits", e);
        }
    }

    /// Reads a decimal integer or float, whose sign, if any, is at `from`.
    private TomlValue decimal(long start, int from) {
        var integerPart = in.position();
        var underscores = digits(DECIMAL_RADIX, "a digit");
        if (in.position() - integerPart > 1 && in.charAt(integerPart) == '0') {
            throw Cursor.error(start, "a number cannot have leading zeros");
        }
        if (!in.isAt('.') && !in.isAt('e') && !in.isAt('E')) {
            return integer(start, from, underscores);
        }
        underscores |= fractionAndExponent();
        var to = in.position();
        return new TomlFloat(Double.parseDouble(underscores ? withoutUnderscores(from, to) : in.text(from, to)));
    }

    /// Moves over the fraction and the exponent of a float, one of which can be missing, and returns whether
    /// there was an underscore.
    private boolean fractionAndExponent() {
        var underscores = false;
        if (in.match('.')) {
            underscores = digits(DECIMAL_RADIX, "a digit after the decimal point");
        }
        if (in.match('e') || in.match('E')) {
            if (in.isAt('+') || in.isAt('-')) {
                in.skip(1);
            }
            underscores |= digits(DECIMAL_RADIX, "a digit of the exponent");
        }
        return underscores;
    }

    /// Parses the decimal integer that ends here, whose sign, if any, is at `from`.
    private TomlValue integer(long start, int from, boolean underscores) {
        var to = in.position();
        try {
            return new TomlInteger(
                    underscores ? Long.parseLong(withoutUnderscores(from, to)) : in.parseLong(from, to, DECIMAL_RADIX));
        } catch (NumberFormatException e) {
            var number = withoutUnderscores(in.charAt(from) == '+' ? from + 1 : from, to);
            throw Cursor.error(start, "the integer " + number + " does not fit in 64 bits", e);
        }
    }

    /// Moves over digits, which can be separated by single underscores, and returns whether there was an
    /// underscore.
    private boolean digits(int radix, String description) {
        if (in.atEnd() || digit(in.peek(), radix) < 0) {
            throw in.expected(description);
        }
        in.skip(1);
        var underscores = false;
        while (!in.atEnd()) {
            if (in.isAt('_')) {
                in.skip(1);
                if (in.atEnd() || digit(in.peek(), radix) < 0) {
                    throw in.error("an underscore must be between two digits");
                }
                underscores = true;
            } else if (digit(in.peek(), radix) < 0) {
                break;
            }
            in.skip(1);
        }
        return underscores;
    }

    /// Returns the text from `from` to `to`, a number, without the underscores between its digits.
    private String withoutUnderscores(int from, int to) {
        var number = new StringBuilder(to - from);
        for (var i = from; i < to; i++) {
            var c = in.charAt(i);
            if (c != '_') {
                number.append(c);
            }
        }
        return number.toString();
    }

    /// Like [Character#digit(char, int)], but only for ASCII, as TOML has no other digits.
    private static int digit(char c, int radix) {
        if (radix == DECIMAL_RADIX) {
            return Cursor.isDigit(c) ? c - '0' : -1;
        }
        return c <= '\u007F' ? Character.digit(c, radix) : -1;
    }

    // Dates and times

    private TomlValue dateTime() {
        var start = in.here();
        try {
            var date = LocalDate.of(fixedDigits(YEAR_DIGITS, "the year"), dashed("the month"), dashed("the day"));
            if (!(in.isAt('T') || in.isAt('t') || (in.isAt(' ') && in.isDigitAt(1)))) {
                return new TomlLocalDate(date);
            }
            in.advance();
            var dateTime = LocalDateTime.of(date, time());
            if (!(in.isAt('Z') || in.isAt('z') || in.isAt('+') || in.isAt('-'))) {
                return new TomlLocalDateTime(dateTime);
            }
            return new TomlOffsetDateTime(OffsetDateTime.of(dateTime, offset()));
        } catch (DateTimeException e) {
            throw Cursor.error(start, "invalid date-time: " + e.getMessage(), e);
        }
    }

    private TomlValue localTime() {
        var start = in.here();
        try {
            return new TomlLocalTime(time());
        } catch (DateTimeException e) {
            throw Cursor.error(start, "invalid time: " + e.getMessage(), e);
        }
    }

    private int dashed(String description) {
        in.expect('-');
        return fixedDigits(2, description);
    }

    private LocalTime time() {
        var hour = fixedDigits(2, "the hour");
        in.expect(':');
        var minute = fixedDigits(2, "the minute");
        var second = 0;
        var nanosecond = 0;
        if (in.match(':')) {
            second = fixedDigits(2, "the second");
            if (in.match('.')) {
                nanosecond = nanosecond();
            }
        }
        return LocalTime.of(hour, minute, second, nanosecond);
    }

    /// Reads a fraction of a second as nanoseconds, truncating the digits past them as TOML requires.
    private int nanosecond() {
        if (!in.isDigitAt(0)) {
            throw in.expected("a digit of the fraction of a second");
        }
        var nanosecond = 0;
        var digits = 0;
        while (in.isDigitAt(0)) {
            var digit = in.advance() - '0';
            if (digits < NANOSECOND_DIGITS) {
                nanosecond = nanosecond * DECIMAL_RADIX + digit;
                digits++;
            }
        }
        for (; digits < NANOSECOND_DIGITS; digits++) {
            nanosecond *= DECIMAL_RADIX;
        }
        return nanosecond;
    }

    private ZoneOffset offset() {
        if (in.match('Z') || in.match('z')) {
            return ZoneOffset.UTC;
        }
        var sign = in.advance() == '-' ? -1 : 1;
        var hours = fixedDigits(2, "the hour of the offset");
        in.expect(':');
        var minutes = fixedDigits(2, "the minute of the offset");
        return ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes);
    }

    private int fixedDigits(int count, String description) {
        if (!in.areDigits(count)) {
            throw in.expected(count + " digits for " + description);
        }
        var value = 0;
        for (var i = 0; i < count; i++) {
            value = value * DECIMAL_RADIX + in.advance() - '0';
        }
        return value;
    }

    // Strings

    /// Reads a basic or literal string, starting at its opening quote.
    private String string(boolean multilineAllowed) {
        var quote = in.advance();
        if (multilineAllowed && in.isAt(quote) && in.isAt(1, quote)) {
            in.advance();
            in.advance();
            return multilineString(quote);
        }
        var basic = quote == '"';
        var from = in.position();
        var to = in.skipText(quote, basic);
        if (in.match(quote)) {
            return in.text(from, to);
        }
        var string = new StringBuilder();
        in.appendTo(string, from, to);
        while (!in.match(quote)) {
            if (in.atEnd() || in.isAt('\n') || in.isAt('\r')) {
                throw in.expected(quote + " to close the string");
            }
            if (!basic || !in.match('\\')) {
                throw controlCharacter();
            }
            escape(string);
            from = in.position();
            in.appendTo(string, from, in.skipText(quote, basic));
        }
        return string.toString();
    }

    private String multilineString(char quote) {
        var string = new StringBuilder();
        if (in.isAt('\n') || in.isAt('\r')) {
            newline();
        }
        var basic = quote == '"';
        while (true) {
            var from = in.position();
            in.appendTo(string, from, in.skipText(quote, basic));
            if (in.atEnd()) {
                throw in.expected(String.valueOf(quote).repeat(MULTILINE_DELIMITER_LENGTH) + " to close the string");
            }
            if (in.isAt(quote)) {
                if (quoteRun(string, quote)) {
                    return string.toString();
                }
            } else if (basic && in.match('\\')) {
                if (!skipLineEndingBackslash()) {
                    escape(string);
                }
            } else if (in.isAt('\n') || in.isAt('\r')) {
                newline();
                string.append('\n');
            } else {
                throw controlCharacter();
            }
        }
    }

    /// Reads a run of quotes in a multi-line string, and returns whether it closes the string. Up to two quotes
    /// can end the content of the string, right before its closing delimiter.
    private boolean quoteRun(StringBuilder string, char quote) {
        var quotes = 0;
        while (in.match(quote)) {
            quotes++;
        }
        if (quotes > MAX_CLOSING_QUOTES) {
            throw in.error("too many quotes at the end of a multi-line string");
        }
        var closes = quotes >= MULTILINE_DELIMITER_LENGTH;
        string.repeat(quote, closes ? quotes - MULTILINE_DELIMITER_LENGTH : quotes);
        return closes;
    }

    /// Skips what follows a line ending backslash, which is whitespace and newlines up to the next other
    /// character, and returns whether there was one; otherwise the backslash starts an escape sequence.
    private boolean skipLineEndingBackslash() {
        var mark = in.mark();
        skipWhitespace();
        if (!in.isAt('\n') && !in.isAt('\r')) {
            in.reset(mark);
            return false;
        }
        while (in.isAt('\n') || in.isAt('\r')) {
            newline();
            skipWhitespace();
        }
        return true;
    }

    /// Reads an escape sequence, after its backslash.
    private void escape(StringBuilder string) {
        if (in.atEnd()) {
            throw in.expected("an escape sequence");
        }
        var c = in.advance();
        var simple = ESCAPES.indexOf(c);
        if (simple >= 0) {
            string.append(ESCAPED.charAt(simple));
        } else {
            var digits = switch (c) {
                case 'x' -> BYTE_ESCAPE_DIGITS;
                case 'u' -> SHORT_UNICODE_ESCAPE_DIGITS;
                case 'U' -> LONG_UNICODE_ESCAPE_DIGITS;
                default -> throw in.error("invalid escape sequence \\" + c);
            };
            string.appendCodePoint(hexadecimalCodePoint(digits));
        }
    }

    private int hexadecimalCodePoint(int digits) {
        var codePoint = 0L;
        for (var i = 0; i < digits; i++) {
            var digit = in.atEnd() ? -1 : digit(in.peek(), HEXADECIMAL_RADIX);
            if (digit < 0) {
                throw in.expected(digits + " hexadecimal digits");
            }
            in.advance();
            codePoint = codePoint * HEXADECIMAL_RADIX + digit;
        }
        if (codePoint > Character.MAX_CODE_POINT
                || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)) {
            throw in.error("U+" + Requirements.hex((int) codePoint) + " is not a Unicode scalar value");
        }
        return (int) codePoint;
    }

    /// An error at the control character where [Cursor#skipText(char, boolean)] stopped in a string or a
    /// comment.
    private TomlParseException controlCharacter() {
        return in.error("the control character U+" + Requirements.hex(in.peek()) + " is not allowed here");
    }

    // Whitespace, comments and newlines

    private void skipWhitespace() {
        while (in.isAt(' ') || in.isAt('\t')) {
            in.advance();
        }
    }

    /// Skips whitespace, comments and newlines.
    private void skipBlank() {
        while (true) {
            skipWhitespace();
            if (in.isAt('#')) {
                comment();
            }
            if (!in.isAt('\n') && !in.isAt('\r')) {
                return;
            }
            newline();
        }
    }

    private void statementEnd() {
        skipWhitespace();
        if (in.isAt('#')) {
            comment();
        }
        if (!in.atEnd() && !in.isAt('\n') && !in.isAt('\r')) {
            throw in.expected("a newline after the statement");
        }
    }

    private void comment() {
        in.expect('#');
        // Nothing but a newline, a control character where skipText stops anyway, ends a comment.
        in.skipText('\n', false);
        if (!in.atEnd() && !in.isAt('\n') && !in.isAt('\r')) {
            throw controlCharacter();
        }
    }

    private void newline() {
        in.match('\r');
        if (!in.match('\n')) {
            throw in.error("a carriage return must be followed by a line feed");
        }
    }

    private static TomlTable freeze(TableNode node) {
        var entries = new ParsedEntries(node.entries.size());
        node.entries.forEach((key, value) -> entries.put(key, freeze(value)));
        return new TomlTable(entries);
    }

    private static TomlValue freeze(Node node) {
        return switch (node) {
            case TableNode table -> freeze(table);
            case ArrayOfTables array -> {
                var elements = new ArrayList<TomlValue>(array.size());
                for (var element : array.elements()) {
                    elements.add(freeze(element));
                }
                yield new TomlArray(elements);
            }
            case Leaf leaf -> leaf.value();
        };
    }

    /// How a table was defined, which decides how it can be extended or defined again.
    private enum Kind {
        /// Defined by a header, or the root table.
        EXPLICIT,
        /// Created as the parent of a table defined by a header, which a header of its own can define.
        IMPLICIT,
        /// Created by a dotted key.
        DOTTED
    }

    /// A value of the tree of tables being built, and where it and its key are written.
    private sealed interface Node extends TomlPositions.Spot permits TableNode, ArrayOfTables, Leaf {}

    /// A table being built, which is also the shape of the table it becomes.
    private static final class TableNode implements Node, TomlPositions.TableShape {

        private final Map<String, Node> entries = new LinkedHashMap<>();
        private Kind kind;
        // Set on a dotted table once a header follows: dotted keys can then no longer extend it.
        private boolean closed;
        private long keyPosition;
        private long valuePosition;

        /// Creates a table that is not the value of a key: the root table, or an inline table.
        TableNode(Kind kind) {
            this.kind = kind;
        }

        TableNode(Kind kind, long keyPosition, long valuePosition) {
            this.kind = kind;
            this.keyPosition = keyPosition;
            this.valuePosition = valuePosition;
        }

        <T extends Node> T add(String key, T node) {
            entries.put(key, node);
            return node;
        }

        @Override
        public long keyPosition() {
            return keyPosition;
        }

        @Override
        public long valuePosition() {
            return valuePosition;
        }

        @Override
        public TableNode shape() {
            return this;
        }

        @Override
        public Map<String, Node> spots() {
            return entries;
        }
    }

    /// An array of tables, defined by `[[header]]`s, being built, with the positions of its headers. It is also
    /// the shape of the array it becomes.
    private record ArrayOfTables(List<TableNode> elements, Positions positions, long keyPosition, long valuePosition)
            implements Node, TomlPositions.ArrayShape {

        @Override
        public ArrayOfTables shape() {
            return this;
        }

        @Override
        public int size() {
            return elements.size();
        }

        @Override
        public long position(int index) {
            return positions.get(index);
        }

        @Override
        public TableNode elementShape(int index) {
            return elements.get(index);
        }
    }

    /// A value that will not change anymore, with the shape of the array or inline table it is.
    private record Leaf(TomlValue value, long keyPosition, long valuePosition, TomlPositions.@Nullable Shape shape)
            implements Node {}

    /// The shape of an array written as a value, which grows as its elements are read.
    private static final class InlineArray implements TomlPositions.ArrayShape {

        private final Positions positions = new Positions();
        private final List<TomlPositions.@Nullable Shape> shapes = new ArrayList<>();

        void add(long position, TomlPositions.@Nullable Shape shape) {
            positions.add(position);
            shapes.add(shape);
        }

        @Override
        public int size() {
            return shapes.size();
        }

        @Override
        public long position(int index) {
            return positions.get(index);
        }

        @Override
        public TomlPositions.@Nullable Shape elementShape(int index) {
            return shapes.get(index);
        }
    }

    /// A part of a dotted key, and where it is written, as [TomlPositions#pack(int, int)] packs it.
    private record Key(String name, long position) {}

    /// Packed positions, which grow as they are read.
    private static final class Positions {

        private long[] positions = new long[INITIAL_POSITIONS];
        private int size;

        void add(long position) {
            if (size == positions.length) {
                positions = Arrays.copyOf(positions, 2 * size);
            }
            positions[size++] = position;
        }

        long get(int index) {
            return positions[index];
        }
    }
}
