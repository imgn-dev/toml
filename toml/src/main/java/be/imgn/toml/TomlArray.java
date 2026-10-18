package be.imgn.toml;

import static java.util.Objects.requireNonNull;

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
}
