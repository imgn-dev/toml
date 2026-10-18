package be.imgn.toml;

import java.io.Serial;

/**
 * Thrown when a value does not bind to the type it is bound to, as when an integer is bound to a {@code String}.
 *
 * @since 0.1.0
 */
public final class TomlMappingException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String path;

    TomlMappingException(String path, String message) {
        super(path.isEmpty() ? message : path + ": " + message);
        this.path = path;
    }

    TomlMappingException(String path, String message, Throwable cause) {
        this(path, message);
        initCause(cause);
    }

    /**
     * Returns where the value is in the document: its keys separated by dots, with the index of each element of an
     * array in brackets, as {@code endpoints[1].path}.
     *
     * @return where the value is, empty for the document itself
     */
    public String path() {
        return path;
    }
}
