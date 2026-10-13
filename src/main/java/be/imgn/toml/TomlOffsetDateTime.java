package be.imgn.toml;

import static java.util.Objects.requireNonNull;

import java.time.OffsetDateTime;

/**
 * A TOML offset date-time, such as {@code 1979-05-27T07:32:00-08:00}.
 *
 * @param value the date-time, in the years 0000 to 9999 and with an offset in whole minutes
 * @since 0.1.0
 */
public record TomlOffsetDateTime(OffsetDateTime value) implements TomlValue {

    /**
     * Creates an offset date-time.
     *
     * @throws NullPointerException if {@code value} is null
     * @throws IllegalArgumentException if the year is outside 0000 to 9999, or the offset has seconds
     */
    public TomlOffsetDateTime {
        requireNonNull(value, "value");
        Requirements.requireFourDigitYear(value.getYear());
        Requirements.requireWholeMinutes(value.getOffset());
    }
}
