package be.imgn.toml;

/**
 * A TOML boolean.
 *
 * @param value the boolean
 * @since 0.1.0
 */
public record TomlBoolean(boolean value) implements TomlValue {}
