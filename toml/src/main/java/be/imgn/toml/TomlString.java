package be.imgn.toml;

import static java.util.Objects.requireNonNull;

/// A TOML string.
///
/// @param value the string, which must be a sequence of Unicode scalar values: an unpaired surrogate has no
///     encoding in a TOML document
/// @since 0.1.0
public record TomlString(String value) implements TomlValue {

    /// Creates a string.
    ///
    /// @throws NullPointerException if `value` is null
    /// @throws IllegalArgumentException if `value` contains an unpaired surrogate
    public TomlString {
        requireNonNull(value, "value");
        if (!Parser.scannedStrings()) {
            Requirements.requireScalarValues(value, "a string");
        }
    }
}
