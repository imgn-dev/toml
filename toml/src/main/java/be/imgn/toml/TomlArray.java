package be.imgn.toml;

import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.List;

/**
 * A TOML array. Its values may be of different types.
 *
 * @param values the values, in order; the list is copied
 * @since 0.1.0
 */
public record TomlArray(List<TomlValue> values) implements TomlValue {

    /**
     * Creates an array.
     *
     * @throws NullPointerException if {@code values} is null, or contains null
     */
    public TomlArray {
        requireNonNull(values, "values");
        var index = 0;
        for (var value : values) {
            requireNonNull(value, "values cannot contain null, as at index " + index);
            index++;
        }
        values = List.copyOf(values);
    }

    /**
     * Returns an array of values.
     *
     * @param values the values, in order; the array is copied
     * @return an array of {@code values}
     * @throws NullPointerException if {@code values} is null, or contains null
     */
    public static TomlArray of(TomlValue... values) {
        requireNonNull(values, "values");
        return new TomlArray(Arrays.asList(values));
    }
}
