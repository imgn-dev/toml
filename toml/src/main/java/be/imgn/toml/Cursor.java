package be.imgn.toml;

/// A position in a text being parsed, which knows its line and column for error messages.
///
/// The cursor reads the characters from a copy of the text in an array, which saves [String#charAt(int)]'s
/// check of the string's coder on every read. It counts lines, but not columns: it keeps where the current line
/// starts, and a column is the distance from there, computed when a position is asked for.
final class Cursor {

    private static final int DECIMAL_RADIX = 10;
    // Any decimal integer of up to 18 digits fits in a long.
    private static final int MAX_SAFE_DECIMAL_DIGITS = 18;

    private final String text;
    private final char[] chars;
    private int position;
    private int line = 1;
    private int lineStart;

    Cursor(String text) {
        this.text = text;
        chars = text.toCharArray();
    }

    boolean atEnd() {
        return position >= chars.length;
    }

    char peek() {
        return chars[position];
    }

    /// Whether the character `ahead` positions from here is `c`, without moving.
    boolean isAt(int ahead, char c) {
        return position + ahead < chars.length && chars[position + ahead] == c;
    }

    boolean isAt(char c) {
        return position < chars.length && chars[position] == c;
    }

    /// Whether the character `ahead` positions from here is an ASCII digit, without moving.
    boolean isDigitAt(int ahead) {
        return position + ahead < chars.length && isDigit(chars[position + ahead]);
    }

    /// Whether the `count` characters from here are ASCII digits, without moving.
    boolean areDigits(int count) {
        for (var i = 0; i < count; i++) {
            if (!isDigitAt(i)) {
                return false;
            }
        }
        return true;
    }

    char advance() {
        var c = chars[position++];
        if (c == '\n') {
            line++;
            lineStart = position;
        }
        return c;
    }

    /// Moves over `count` characters, which must not be line feeds.
    void skip(int count) {
        position += count;
    }

    boolean match(char c) {
        if (isAt(c)) {
            advance();
            return true;
        }
        return false;
    }

    void expect(char c) {
        if (!match(c)) {
            throw expected("'" + c + "'");
        }
    }

    void expectWord(String word) {
        for (var i = 0; i < word.length(); i++) {
            if (!match(word.charAt(i))) {
                throw expected("'" + word + "'");
            }
        }
    }

    /// The index of the next character in the text.
    int position() {
        return position;
    }

    char charAt(int index) {
        return chars[index];
    }

    /// The text from `from` to `to`, as indexes [#position()] returned.
    String text(int from, int to) {
        return text.substring(from, to);
    }

    /// Parses the text from `from` to `to`, as [Long#parseLong(CharSequence, int, int, int)] does. The text must
    /// be digits in `radix` after an optional sign, as the parser has already checked.
    long parseLong(int from, int to, int radix) {
        var i = from;
        var negative = chars[i] == '-';
        if (negative || chars[i] == '+') {
            i++;
        }
        // A decimal integer that short needs neither the digit nor the overflow checks Long.parseLong makes.
        if (radix != DECIMAL_RADIX || i == to || to - i > MAX_SAFE_DECIMAL_DIGITS) {
            return Long.parseLong(text, from, to, radix);
        }
        var value = 0L;
        for (; i < to; i++) {
            value = value * DECIMAL_RADIX + (chars[i] - '0');
        }
        return negative ? -value : value;
    }

    /// Appends the text from `from` to `to` to `builder`.
    void appendTo(StringBuilder builder, int from, int to) {
        builder.append(chars, from, to - from);
    }

    /// Moves over the characters of a bare key, and returns where they end.
    ///
    /// @return the index after the last character of the bare key
    int skipBareKey() {
        var i = position;
        while (i < chars.length && isBareKeyChar(chars[i])) {
            i++;
        }
        position = i;
        return i;
    }

    /// Moves over the characters that a string or a comment holds as they are written, and returns where they
    /// end. It stops before `stop`, a backslash if `escapes`, a control character other than tab, which
    /// includes the newlines, or the end of the text.
    ///
    /// @return the index after the last character moved over
    /// @throws TomlParseException at an unpaired surrogate, which has no UTF-8 encoding and so cannot be in a
    ///     document
    int skipText(char stop, boolean escapes) {
        var i = position;
        while (i < chars.length) {
            var c = chars[i];
            if (c == stop || (escapes && c == '\\') || isControl(c)) {
                break;
            }
            if (Character.isSurrogate(c)) {
                i = endOfSurrogatePair(i);
            }
            i++;
        }
        position = i;
        return i;
    }

    /// Returns the index of the low surrogate that pairs with the high surrogate at `index`.
    private int endOfSurrogatePair(int index) {
        var c = chars[index];
        if (!Character.isHighSurrogate(c) || index + 1 == chars.length || !Character.isLowSurrogate(chars[index + 1])) {
            position = index;
            throw error("the unpaired surrogate U+" + Requirements.hex(c) + " is not allowed");
        }
        return index + 1;
    }

    /// Whether a character is a control character other than tab, as the newlines are.
    private static boolean isControl(char c) {
        return c < ' ' ? c != '\t' : c == '\u007F';
    }

    /// Where the cursor is, as [TomlPositions#pack(int, int)] packs it.
    long here() {
        return TomlPositions.pack(line, column());
    }

    Mark mark() {
        return new Mark(position, line, lineStart);
    }

    void reset(Mark mark) {
        position = mark.position();
        line = mark.line();
        lineStart = mark.lineStart();
    }

    TomlParseException error(String message) {
        return new TomlParseException(message, line, column());
    }

    TomlParseException error(String message, Throwable cause) {
        return new TomlParseException(message, line, column(), cause);
    }

    /// An error at a position read earlier, such as the key a semantic rule rejects.
    static TomlParseException error(long position, String message) {
        var at = TomlPositions.unpack(position);
        return new TomlParseException(message, at.line(), at.column());
    }

    static TomlParseException error(long position, String message, Throwable cause) {
        var at = TomlPositions.unpack(position);
        return new TomlParseException(message, at.line(), at.column(), cause);
    }

    /// An error that states what the grammar wanted and what is actually there.
    TomlParseException expected(String description) {
        return error("expected " + description + " but found " + (atEnd() ? "end of input" : describe(peek())));
    }

    static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    static boolean isBareKeyChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || isDigit(c) || c == '_' || c == '-';
    }

    private int column() {
        return position - lineStart + 1;
    }

    private static String describe(char c) {
        return switch (c) {
            case '\n' -> "'\\n'";
            case '\r' -> "'\\r'";
            case '\t' -> "'\\t'";
            default -> c < ' ' || c == '\u007F' ? "U+" + Requirements.hex(c) : "'" + c + "'";
        };
    }

    /// A saved position, to go back to, with the line it is on and where that line starts.
    record Mark(int position, int line, int lineStart) {}
}
