package be.imgn.toml;

import static be.imgn.gaia.core.base.Throwables.illegalArgument;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A recursive-descent TOML 1.1 parser.
 *
 * <p>The tables a document defines with headers and dotted keys are built as a mutable tree, which records how each
 * table was defined so that the rules against redefining a table can be enforced, and which becomes {@link TomlTable}s
 * at the end. Arrays and inline tables cannot be extended once closed, so they become values right away.
 */
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

    private final Cursor in;
    private final TableNode root = new TableNode(Kind.EXPLICIT);
    private final List<TableNode> openDottedTables = new ArrayList<>();
    private TableNode current = root;

    private Parser(String text) {
        in = new Cursor(text);
    }

    static TomlTable parse(String document) {
        var text = !document.isEmpty() && document.charAt(0) == BYTE_ORDER_MARK ? document.substring(1) : document;
        return new Parser(text).document();
    }

    /** Parses a dotted key as written in a document, such as {@code a."b.c"}, into its parts. */
    static List<String> parseKey(String key) {
        var parser = new Parser(key);
        try {
            var keys = parser.keyPath();
            if (!parser.in.atEnd()) {
                throw parser.in.expected("'.' or the end of the key");
            }
            return keys;
        } catch (TomlParseException e) {
            throw illegalArgument(e, "invalid key %s: %s", key, e.getMessage());
        }
    }

    private TomlTable document() {
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
        return freeze(root);
    }

    // Tables and keys

    private void header() {
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
        current = arrayOfTables ? appendTable(parent, keys.getLast()) : defineTable(parent, keys.getLast());
    }

    private TableNode parentOf(List<String> keys) {
        var table = root;
        for (var key : keys.subList(0, keys.size() - 1)) {
            var existing = table.entries.get(key);
            table = switch (existing) {
                case null -> table.add(key, new TableNode(Kind.IMPLICIT));
                case TableNode node -> node;
                case ArrayOfTables array -> array.elements().getLast();
                case Leaf leaf -> throw notATable(key, leaf);
            };
        }
        return table;
    }

    private TableNode appendTable(TableNode parent, String key) {
        var array = switch (parent.entries.get(key)) {
            case null -> parent.add(key, new ArrayOfTables(new ArrayList<>()));
            case ArrayOfTables existing -> existing;
            default -> throw in.error("cannot define '" + key + "' again as an array of tables");
        };
        var element = new TableNode(Kind.EXPLICIT);
        array.elements().add(element);
        return element;
    }

    private TableNode defineTable(TableNode parent, String key) {
        var existing = parent.entries.get(key);
        if (existing == null) {
            return parent.add(key, new TableNode(Kind.EXPLICIT));
        }
        if (existing instanceof TableNode table && table.kind == Kind.IMPLICIT) {
            table.kind = Kind.EXPLICIT;
            return table;
        }
        throw in.error("the table '" + key + "' is already defined");
    }

    private void keyValue(TableNode base) {
        var keys = keyPath();
        in.expect('=');
        skipWhitespace();
        var value = value();
        var table = base;
        for (var key : keys.subList(0, keys.size() - 1)) {
            var existing = table.entries.get(key);
            table = switch (existing) {
                case null -> openDottedTable(table, key);
                case TableNode node when node.kind != Kind.EXPLICIT && !node.closed -> node;
                case TableNode node -> throw in.error("cannot extend the table '" + key + "' with a dotted key");
                case ArrayOfTables array -> throw notATable(key, array);
                case Leaf leaf -> throw notATable(key, leaf);
            };
        }
        var last = keys.getLast();
        if (table.entries.containsKey(last)) {
            throw in.error("duplicate key '" + last + "'");
        }
        table.add(last, new Leaf(value));
    }

    private TableNode openDottedTable(TableNode parent, String key) {
        var table = parent.add(key, new TableNode(Kind.DOTTED));
        openDottedTables.add(table);
        return table;
    }

    private TomlParseException notATable(String key, Node node) {
        return node instanceof Leaf leaf && leaf.value() instanceof TomlTable
                ? in.error("cannot extend the inline table '" + key + "'")
                : in.error("the key '" + key + "' is not a table");
    }

    private List<String> keyPath() {
        var keys = new ArrayList<String>();
        do {
            skipWhitespace();
            keys.add(key());
            skipWhitespace();
        } while (in.match('.'));
        return keys;
    }

    private String key() {
        if (in.isAt('"') || in.isAt('\'')) {
            return string(false);
        }
        if (in.atEnd() || !isBareKeyChar(in.peek())) {
            throw in.expected("a key");
        }
        var key = new StringBuilder();
        while (!in.atEnd() && isBareKeyChar(in.peek())) {
            key.append(in.advance());
        }
        return key.toString();
    }

    private static boolean isBareKeyChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || Cursor.isDigit(c) || c == '_' || c == '-';
    }

    // Values

    private TomlValue value() {
        if (in.atEnd()) {
            throw in.expected("a value");
        }
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
        skipBlank();
        while (!in.match(']')) {
            values.add(value());
            skipBlank();
            if (!in.match(',') && !in.isAt(']')) {
                throw in.expected("',' or ']'");
            }
            skipBlank();
        }
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
        return freeze(table);
    }

    // Numbers

    private TomlValue number() {
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
                throw in.error("a hexadecimal, octal or binary integer cannot have a sign");
            }
            return radixInteger();
        }
        return decimal(negative);
    }

    private TomlValue infinityOrNan(boolean negative) {
        if (in.isAt('i')) {
            in.expectWord("inf");
            return new TomlFloat(negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY);
        }
        in.expectWord("nan");
        return new TomlFloat(Double.NaN);
    }

    private TomlValue radixInteger() {
        in.advance();
        var prefix = in.advance();
        var radix = switch (prefix) {
            case 'x' -> HEXADECIMAL_RADIX;
            case 'o' -> OCTAL_RADIX;
            default -> BINARY_RADIX;
        };
        var digits = digits(radix, "a digit in base " + radix);
        try {
            return new TomlInteger(Long.parseLong(digits, radix));
        } catch (NumberFormatException e) {
            throw in.error("the integer 0" + prefix + digits + " does not fit in 64 bits", e);
        }
    }

    private TomlValue decimal(boolean negative) {
        var integerPart = digits(DECIMAL_RADIX, "a digit");
        if (integerPart.length() > 1 && integerPart.charAt(0) == '0') {
            throw in.error("a number cannot have leading zeros");
        }
        var number = new StringBuilder(negative ? "-" : "").append(integerPart);
        var isFloat = false;
        if (in.match('.')) {
            isFloat = true;
            number.append('.').append(digits(DECIMAL_RADIX, "a digit after the decimal point"));
        }
        if (in.match('e') || in.match('E')) {
            isFloat = true;
            number.append('e');
            if (in.isAt('+') || in.isAt('-')) {
                number.append(in.advance());
            }
            number.append(digits(DECIMAL_RADIX, "a digit of the exponent"));
        }
        if (isFloat) {
            return new TomlFloat(Double.parseDouble(number.toString()));
        }
        try {
            return new TomlInteger(Long.parseLong(number.toString()));
        } catch (NumberFormatException e) {
            throw in.error("the integer " + number + " does not fit in 64 bits", e);
        }
    }

    /** Reads digits, which can be separated by single underscores, and returns them without the underscores. */
    private String digits(int radix, String description) {
        if (in.atEnd() || digit(in.peek(), radix) < 0) {
            throw in.expected(description);
        }
        var digits = new StringBuilder().append(in.advance());
        while (!in.atEnd() && (in.isAt('_') || digit(in.peek(), radix) >= 0)) {
            if (in.match('_') && (in.atEnd() || digit(in.peek(), radix) < 0)) {
                throw in.error("an underscore must be between two digits");
            }
            digits.append(in.advance());
        }
        return digits.toString();
    }

    /** Like {@link Character#digit(char, int)}, but only for ASCII, as TOML has no other digits. */
    private static int digit(char c, int radix) {
        return c <= '\u007F' ? Character.digit(c, radix) : -1;
    }

    // Dates and times

    private TomlValue dateTime() {
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
            throw in.error("invalid date-time: " + e.getMessage(), e);
        }
    }

    private TomlValue localTime() {
        try {
            return new TomlLocalTime(time());
        } catch (DateTimeException e) {
            throw in.error("invalid time: " + e.getMessage(), e);
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

    /** Reads a fraction of a second as nanoseconds, truncating the digits past them as TOML requires. */
    private int nanosecond() {
        if (!in.isDigitAt(0)) {
            throw in.expected("a digit of the fraction of a second");
        }
        var digits = new StringBuilder();
        while (in.isDigitAt(0)) {
            digits.append(in.advance());
        }
        digits.append("0".repeat(NANOSECOND_DIGITS));
        return Integer.parseInt(digits, 0, NANOSECOND_DIGITS, DECIMAL_RADIX);
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

    /** Reads a basic or literal string, starting at its opening quote. */
    private String string(boolean multilineAllowed) {
        var quote = in.advance();
        if (multilineAllowed && in.isAt(quote) && in.isAt(1, quote)) {
            in.advance();
            in.advance();
            return multilineString(quote);
        }
        var string = new StringBuilder();
        while (!in.match(quote)) {
            if (in.atEnd() || in.isAt('\n') || in.isAt('\r')) {
                throw in.expected(quote + " to close the string");
            }
            if (quote == '"' && in.match('\\')) {
                escape(string);
            } else {
                string.append(content());
            }
        }
        return string.toString();
    }

    private String multilineString(char quote) {
        var string = new StringBuilder();
        if (in.isAt('\n') || in.isAt('\r')) {
            newline();
        }
        while (true) {
            if (in.atEnd()) {
                throw in.expected(String.valueOf(quote).repeat(MULTILINE_DELIMITER_LENGTH) + " to close the string");
            }
            if (in.isAt(quote)) {
                if (quoteRun(string, quote)) {
                    return string.toString();
                }
            } else if (quote == '"' && in.match('\\')) {
                if (!skipLineEndingBackslash()) {
                    escape(string);
                }
            } else if (in.isAt('\n') || in.isAt('\r')) {
                newline();
                string.append('\n');
            } else {
                string.append(content());
            }
        }
    }

    /**
     * Reads a run of quotes in a multi-line string, and returns whether it closes the string. Up to two quotes can end
     * the content of the string, right before its closing delimiter.
     */
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

    /**
     * Skips what follows a line ending backslash, which is whitespace and newlines up to the next other character, and
     * returns whether there was one; otherwise the backslash starts an escape sequence.
     */
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

    /** Reads an escape sequence, after its backslash. */
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

    /** Reads a character of a string or a comment, which cannot be a control character other than tab. */
    private char content() {
        var c = in.peek();
        if ((c < ' ' && c != '\t') || c == '\u007F') {
            throw in.error("the control character U+" + Requirements.hex(c) + " is not allowed here");
        }
        return in.advance();
    }

    // Whitespace, comments and newlines

    private void skipWhitespace() {
        while (in.isAt(' ') || in.isAt('\t')) {
            in.advance();
        }
    }

    /** Skips whitespace, comments and newlines. */
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
        while (!in.atEnd() && !in.isAt('\n') && !in.isAt('\r')) {
            content();
        }
    }

    private void newline() {
        in.match('\r');
        if (!in.match('\n')) {
            throw in.error("a carriage return must be followed by a line feed");
        }
    }

    private static TomlTable freeze(TableNode node) {
        var entries = LinkedHashMap.<String, TomlValue>newLinkedHashMap(node.entries.size());
        node.entries.forEach((key, value) -> entries.put(key, freeze(value)));
        return new TomlTable(entries);
    }

    private static TomlValue freeze(Node node) {
        return switch (node) {
            case TableNode table -> freeze(table);
            case ArrayOfTables array ->
                new TomlArray(
                        array.elements().stream().<TomlValue>map(Parser::freeze).toList());
            case Leaf leaf -> leaf.value();
        };
    }

    /** How a table was defined, which decides how it can be extended or defined again. */
    private enum Kind {
        /** Defined by a header, or the root table. */
        EXPLICIT,
        /** Created as the parent of a table defined by a header, which a header of its own can define. */
        IMPLICIT,
        /** Created by a dotted key. */
        DOTTED
    }

    /** A value of the tree of tables being built. */
    private sealed interface Node permits TableNode, ArrayOfTables, Leaf {}

    /** A table being built. */
    private static final class TableNode implements Node {

        private final Map<String, Node> entries = new LinkedHashMap<>();
        private Kind kind;
        // Set on a dotted table once a header follows: dotted keys can then no longer extend it.
        private boolean closed;

        TableNode(Kind kind) {
            this.kind = kind;
        }

        <T extends Node> T add(String key, T node) {
            entries.put(key, node);
            return node;
        }
    }

    /** An array of tables, defined by {@code [[header]]}s, being built. */
    private record ArrayOfTables(List<TableNode> elements) implements Node {}

    /** A value that will not change anymore. */
    private record Leaf(TomlValue value) implements Node {}
}
