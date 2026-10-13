package be.imgn.toml;

/**
 * A TOML integer.
 *
 * @param value the integer
 * @since 0.1.0
 */
public record TomlInteger(long value) implements TomlValue {}
