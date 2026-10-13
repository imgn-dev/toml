package be.imgn.toml;

/** A position in a text being parsed, which knows its line and column for error messages. */
final class Cursor {

    private final String text;
    private int position;
    private int line = 1;
    private int column = 1;

    Cursor(String text) {
        this.text = text;
    }

    boolean atEnd() {
        return position >= text.length();
    }

    char peek() {
        return text.charAt(position);
    }

    /** Whether the character {@code ahead} positions from here is {@code c}, without moving. */
    boolean isAt(int ahead, char c) {
        return position + ahead < text.length() && text.charAt(position + ahead) == c;
    }

    boolean isAt(char c) {
        return isAt(0, c);
    }

    /** Whether the character {@code ahead} positions from here is an ASCII digit, without moving. */
    boolean isDigitAt(int ahead) {
        return position + ahead < text.length() && isDigit(text.charAt(position + ahead));
    }

    /** Whether the {@code count} characters from here are ASCII digits, without moving. */
    boolean areDigits(int count) {
        for (var i = 0; i < count; i++) {
            if (!isDigitAt(i)) {
                return false;
            }
        }
        return true;
    }

    char advance() {
        var c = text.charAt(position++);
        if (c == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
        return c;
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

    Mark mark() {
        return new Mark(position, line, column);
    }

    void reset(Mark mark) {
        position = mark.position();
        line = mark.line();
        column = mark.column();
    }

    TomlParseException error(String message) {
        return new TomlParseException(message, line, column);
    }

    TomlParseException error(String message, Throwable cause) {
        return new TomlParseException(message, line, column, cause);
    }

    /** An error that states what the grammar wanted and what is actually there. */
    TomlParseException expected(String description) {
        return error("expected " + description + " but found " + (atEnd() ? "end of input" : describe(peek())));
    }

    static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static String describe(char c) {
        return switch (c) {
            case '\n' -> "'\\n'";
            case '\r' -> "'\\r'";
            case '\t' -> "'\\t'";
            default -> c < ' ' || c == '\u007F' ? "U+" + Requirements.hex(c) : "'" + c + "'";
        };
    }

    /** A saved position, to go back to. */
    record Mark(int position, int line, int column) {}
}
