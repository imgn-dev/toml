package be.imgn.toml;

import java.io.Serial;

/**
 * Thrown when a document is not valid TOML.
 *
 * <p>The message starts with the line and column of the error, and states what was expected and what was found.
 *
 * @since 0.1.0
 */
public final class TomlParseException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int line;
    private final int column;

    TomlParseException(String message, int line, int column) {
        super("line " + line + ", column " + column + ": " + message);
        this.line = line;
        this.column = column;
    }

    TomlParseException(String message, int line, int column, Throwable cause) {
        this(message, line, column);
        initCause(cause);
    }

    /**
     * Returns where the error is.
     *
     * @return the position of the error
     */
    public TomlPosition position() {
        return new TomlPosition(line, column);
    }
}
