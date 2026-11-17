package be.imgn.toml;

import static java.util.Objects.requireNonNull;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/// A TOML table: keys mapped to values, in the order the keys were given or appeared in the document.
///
/// Besides [#entries()], a table looks its values up by a TOML key, which can be dotted and quoted as in a
/// document. `get("server.port")` is the value of the key `port` in the table `server`, and
/// `get("site.\"example.com\"")` the value of the key `example.com` in the table `site`. The typed
/// getters, such as [#getString(String)], also check the type of the value.
///
/// Two tables are equal when they have the same entries, whatever their order.
///
/// @param entries the entries, which keep their iteration order; the map is copied
/// @since 0.1.0
public record TomlTable(Map<String, TomlValue> entries) implements TomlValue {

    /// Creates a table.
    ///
    /// @throws NullPointerException if `entries` is null, or contains a null key or value
    /// @throws IllegalArgumentException if a key contains an unpaired surrogate
    public TomlTable {
        requireNonNull(entries, "entries");
        // The parser's entries are checked already, and nothing else holds them.
        entries = Collections.unmodifiableSequencedMap(
                entries instanceof ParsedEntries parsed ? parsed : checkedCopy(entries));
    }

    private static LinkedHashMap<String, TomlValue> checkedCopy(Map<String, TomlValue> entries) {
        var copy = LinkedHashMap.<String, TomlValue>newLinkedHashMap(entries.size());
        entries.forEach((key, value) -> {
            requireNonNull(key, "entries cannot contain a null key");
            Requirements.requireScalarValues(key, "a key");
            copy.put(key, requireNonNull(value, () -> "entries cannot contain a null value, as for the key " + key));
        });
        return copy;
    }

    /// Returns a builder of tables, which keeps the entries in the order they are put.
    ///
    /// @return a new builder
    public static Builder builder() {
        return new Builder();
    }

    /// Returns a builder that starts with the entries of this table, in their order. The builder does not
    /// change this table.
    ///
    /// @return a new builder
    public Builder toBuilder() {
        var builder = new Builder();
        builder.entries.putAll(entries);
        return builder;
    }

    /// Returns the value of a key.
    ///
    /// @param key a key, which can be dotted and quoted as in a document
    /// @return the value of `key`, or empty if this table has none
    /// @throws NullPointerException if `key` is null
    /// @throws IllegalArgumentException if `key` is not a valid TOML key
    public Optional<TomlValue> get(String key) {
        requireNonNull(key, "key");
        TomlValue value = this;
        for (var part : Parser.parseKey(key)) {
            if (!(value instanceof TomlTable table) || !table.entries.containsKey(part)) {
                return Optional.empty();
            }
            value = requireNonNull(table.entries.get(part));
        }
        return Optional.of(value);
    }

    /// Returns the table that is the value of a key.
    ///
    /// @param key a key, which can be dotted and quoted as in a document
    /// @return the value of `key`
    /// @throws NullPointerException if `key` is null
    /// @throws IllegalArgumentException if `key` is not a valid TOML key
    /// @throws NoSuchElementException if this table has no value for `key`
    /// @throws ClassCastException if the value of `key` is not a table
    public TomlTable getTable(String key) {
        return require(key, TomlTable.class);
    }

    /// Returns the array that is the value of a key.
    ///
    /// @param key a key, which can be dotted and quoted as in a document
    /// @return the value of `key`
    /// @throws NullPointerException if `key` is null
    /// @throws IllegalArgumentException if `key` is not a valid TOML key
    /// @throws NoSuchElementException if this table has no value for `key`
    /// @throws ClassCastException if the value of `key` is not an array
    public TomlArray getArray(String key) {
        return require(key, TomlArray.class);
    }

    /// Returns the string that is the value of a key.
    ///
    /// @param key a key, which can be dotted and quoted as in a document
    /// @return the value of `key`
    /// @throws NullPointerException if `key` is null
    /// @throws IllegalArgumentException if `key` is not a valid TOML key
    /// @throws NoSuchElementException if this table has no value for `key`
    /// @throws ClassCastException if the value of `key` is not a string
    public String getString(String key) {
        return require(key, TomlString.class).value();
    }

    /// Returns the integer that is the value of a key.
    ///
    /// @param key a key, which can be dotted and quoted as in a document
    /// @return the value of `key`
    /// @throws NullPointerException if `key` is null
    /// @throws IllegalArgumentException if `key` is not a valid TOML key
    /// @throws NoSuchElementException if this table has no value for `key`
    /// @throws ClassCastException if the value of `key` is not an integer
    public long getLong(String key) {
        return require(key, TomlInteger.class).value();
    }

    /// Returns the integer that is the value of a key, as an `int`.
    ///
    /// @param key a key, which can be dotted and quoted as in a document
    /// @return the value of `key`
    /// @throws NullPointerException if `key` is null
    /// @throws IllegalArgumentException if `key` is not a valid TOML key
    /// @throws NoSuchElementException if this table has no value for `key`
    /// @throws ClassCastException if the value of `key` is not an integer
    /// @throws ArithmeticException if the value of `key` is out of the range of `int`
    public int getInt(String key) {
        var value = getLong(key);
        if (value != (int) value) {
            throw new ArithmeticException("the value of the key " + key + " is " + value + ", out of the range of int");
        }
        return (int) value;
    }

    /// Returns the float that is the value of a key.
    ///
    /// @param key a key, which can be dotted and quoted as in a document
    /// @return the value of `key`
    /// @throws NullPointerException if `key` is null
    /// @throws IllegalArgumentException if `key` is not a valid TOML key
    /// @throws NoSuchElementException if this table has no value for `key`
    /// @throws ClassCastException if the value of `key` is not a float
    public double getDouble(String key) {
        return require(key, TomlFloat.class).value();
    }

    /// Returns the boolean that is the value of a key.
    ///
    /// @param key a key, which can be dotted and quoted as in a document
    /// @return the value of `key`
    /// @throws NullPointerException if `key` is null
    /// @throws IllegalArgumentException if `key` is not a valid TOML key
    /// @throws NoSuchElementException if this table has no value for `key`
    /// @throws ClassCastException if the value of `key` is not a boolean
    public boolean getBoolean(String key) {
        return require(key, TomlBoolean.class).value();
    }

    /// Returns the offset date-time that is the value of a key.
    ///
    /// @param key a key, which can be dotted and quoted as in a document
    /// @return the value of `key`
    /// @throws NullPointerException if `key` is null
    /// @throws IllegalArgumentException if `key` is not a valid TOML key
    /// @throws NoSuchElementException if this table has no value for `key`
    /// @throws ClassCastException if the value of `key` is not an offset date-time
    public OffsetDateTime getOffsetDateTime(String key) {
        return require(key, TomlOffsetDateTime.class).value();
    }

    /// Returns the local date-time that is the value of a key.
    ///
    /// @param key a key, which can be dotted and quoted as in a document
    /// @return the value of `key`
    /// @throws NullPointerException if `key` is null
    /// @throws IllegalArgumentException if `key` is not a valid TOML key
    /// @throws NoSuchElementException if this table has no value for `key`
    /// @throws ClassCastException if the value of `key` is not a local date-time
    public LocalDateTime getLocalDateTime(String key) {
        return require(key, TomlLocalDateTime.class).value();
    }

    /// Returns the local date that is the value of a key.
    ///
    /// @param key a key, which can be dotted and quoted as in a document
    /// @return the value of `key`
    /// @throws NullPointerException if `key` is null
    /// @throws IllegalArgumentException if `key` is not a valid TOML key
    /// @throws NoSuchElementException if this table has no value for `key`
    /// @throws ClassCastException if the value of `key` is not a local date
    public LocalDate getLocalDate(String key) {
        return require(key, TomlLocalDate.class).value();
    }

    /// Returns the local time that is the value of a key.
    ///
    /// @param key a key, which can be dotted and quoted as in a document
    /// @return the value of `key`
    /// @throws NullPointerException if `key` is null
    /// @throws IllegalArgumentException if `key` is not a valid TOML key
    /// @throws NoSuchElementException if this table has no value for `key`
    /// @throws ClassCastException if the value of `key` is not a local time
    public LocalTime getLocalTime(String key) {
        return require(key, TomlLocalTime.class).value();
    }

    private <T extends TomlValue> T require(String key, Class<T> type) {
        var value = get(key).orElseThrow(() -> new NoSuchElementException("no value for the key " + key));
        if (!type.isInstance(value)) {
            throw new ClassCastException("the value of the key " + key + " is a "
                    + value.getClass().getSimpleName() + ", not a " + type.getSimpleName());
        }
        return type.cast(value);
    }

    /// Builds a [TomlTable], keeping its entries in the order they are put.
    ///
    /// @since 0.1.0
    public static final class Builder {

        private final Map<String, TomlValue> entries = new LinkedHashMap<>();

        private Builder() {}

        /// Puts an entry, replacing the value of a key that is already in the table but keeping its position.
        ///
        /// @param key the key, taken as is: a dot is part of the key and does not nest tables
        /// @param value the value
        /// @return this builder
        /// @throws NullPointerException if `key` or `value` is null
        /// @throws IllegalArgumentException if `key` contains an unpaired surrogate
        public Builder put(String key, TomlValue value) {
            requireNonNull(key, "key");
            Requirements.requireScalarValues(key, "a key");
            entries.put(key, requireNonNull(value, "value"));
            return this;
        }

        /// Puts a string, replacing the value of a key that is already in the table but keeping its position.
        ///
        /// @param key the key, taken as is: a dot is part of the key and does not nest tables
        /// @param value the string
        /// @return this builder
        /// @throws NullPointerException if `key` or `value` is null
        /// @throws IllegalArgumentException if `key` or `value` contains an unpaired surrogate
        /// @see TomlString
        public Builder put(String key, String value) {
            return put(key, new TomlString(value));
        }

        /// Puts an integer, replacing the value of a key that is already in the table but keeping its position.
        ///
        /// @param key the key, taken as is: a dot is part of the key and does not nest tables
        /// @param value the integer
        /// @return this builder
        /// @throws NullPointerException if `key` is null
        /// @throws IllegalArgumentException if `key` contains an unpaired surrogate
        /// @see TomlInteger
        public Builder put(String key, long value) {
            return put(key, new TomlInteger(value));
        }

        /// Puts a float, replacing the value of a key that is already in the table but keeping its position.
        ///
        /// @param key the key, taken as is: a dot is part of the key and does not nest tables
        /// @param value the float, which may be infinite or NaN
        /// @return this builder
        /// @throws NullPointerException if `key` is null
        /// @throws IllegalArgumentException if `key` contains an unpaired surrogate
        /// @see TomlFloat
        public Builder put(String key, double value) {
            return put(key, new TomlFloat(value));
        }

        /// Puts a boolean, replacing the value of a key that is already in the table but keeping its position.
        ///
        /// @param key the key, taken as is: a dot is part of the key and does not nest tables
        /// @param value the boolean
        /// @return this builder
        /// @throws NullPointerException if `key` is null
        /// @throws IllegalArgumentException if `key` contains an unpaired surrogate
        /// @see TomlBoolean
        public Builder put(String key, boolean value) {
            return put(key, new TomlBoolean(value));
        }

        /// Puts an offset date-time, replacing the value of a key that is already in the table but keeping its
        /// position.
        ///
        /// @param key the key, taken as is: a dot is part of the key and does not nest tables
        /// @param value the offset date-time
        /// @return this builder
        /// @throws NullPointerException if `key` or `value` is null
        /// @throws IllegalArgumentException if `key` contains an unpaired surrogate, or if TOML cannot
        ///     represent `value`
        /// @see TomlOffsetDateTime
        public Builder put(String key, OffsetDateTime value) {
            return put(key, new TomlOffsetDateTime(value));
        }

        /// Puts a local date-time, replacing the value of a key that is already in the table but keeping its
        /// position.
        ///
        /// @param key the key, taken as is: a dot is part of the key and does not nest tables
        /// @param value the local date-time
        /// @return this builder
        /// @throws NullPointerException if `key` or `value` is null
        /// @throws IllegalArgumentException if `key` contains an unpaired surrogate, or if TOML cannot
        ///     represent `value`
        /// @see TomlLocalDateTime
        public Builder put(String key, LocalDateTime value) {
            return put(key, new TomlLocalDateTime(value));
        }

        /// Puts a local date, replacing the value of a key that is already in the table but keeping its
        /// position.
        ///
        /// @param key the key, taken as is: a dot is part of the key and does not nest tables
        /// @param value the local date
        /// @return this builder
        /// @throws NullPointerException if `key` or `value` is null
        /// @throws IllegalArgumentException if `key` contains an unpaired surrogate, or if TOML cannot
        ///     represent `value`
        /// @see TomlLocalDate
        public Builder put(String key, LocalDate value) {
            return put(key, new TomlLocalDate(value));
        }

        /// Puts a local time, replacing the value of a key that is already in the table but keeping its
        /// position.
        ///
        /// @param key the key, taken as is: a dot is part of the key and does not nest tables
        /// @param value the local time
        /// @return this builder
        /// @throws NullPointerException if `key` or `value` is null
        /// @throws IllegalArgumentException if `key` contains an unpaired surrogate
        /// @see TomlLocalTime
        public Builder put(String key, LocalTime value) {
            return put(key, new TomlLocalTime(value));
        }

        /// Builds the table. The builder can still be used afterwards, without changing that table.
        ///
        /// @return a table of the entries put so far
        public TomlTable build() {
            return new TomlTable(entries);
        }
    }
}
