package be.imgn.toml;

import static java.util.Objects.requireNonNull;

import java.time.LocalTime;

/**
 * A TOML local time, such as {@code 07:32:00}.
 *
 * @param value the time
 * @since 0.1.0
 */
public record TomlLocalTime(LocalTime value) implements TomlValue {

    /**
     * Creates a local time.
     *
     * @throws NullPointerException if {@code value} is null
     */
    public TomlLocalTime {
        requireNonNull(value, "value");
    }
}
