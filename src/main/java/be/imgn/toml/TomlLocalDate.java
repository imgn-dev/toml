package be.imgn.toml;

import static java.util.Objects.requireNonNull;

import java.time.LocalDate;

/**
 * A TOML local date, such as {@code 1979-05-27}.
 *
 * @param value the date, in the years 0000 to 9999
 * @since 0.1.0
 */
public record TomlLocalDate(LocalDate value) implements TomlValue {

    /**
     * Creates a local date.
     *
     * @throws NullPointerException if {@code value} is null
     * @throws IllegalArgumentException if the year is outside 0000 to 9999
     */
    public TomlLocalDate {
        requireNonNull(value, "value");
        Requirements.requireFourDigitYear(value.getYear());
    }
}
