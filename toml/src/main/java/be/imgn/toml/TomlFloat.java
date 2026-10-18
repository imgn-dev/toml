package be.imgn.toml;

/**
 * A TOML float.
 *
 * <p>As with every record, two floats are equal when their values are equal by {@link Double#compare}: NaN equals NaN,
 * and {@code 0.0} does not equal {@code -0.0}.
 *
 * @param value the float, which may be infinite or NaN
 * @since 0.1.0
 */
public record TomlFloat(double value) implements TomlValue {}
