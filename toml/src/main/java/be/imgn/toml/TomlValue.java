package be.imgn.toml;

/// A TOML value: a table, an array, or a scalar.
///
/// Values are immutable, and each one can be written as TOML: the constructors reject what TOML cannot
/// represent, such as a date outside the years 0000 to 9999.
///
/// The hierarchy is sealed, so a `switch` over a value can cover every type:
///
/// ```java
/// String describe(TomlValue value) {
///     return switch (value) {
///         case TomlString(var s) -> "the string " + s;
///         case TomlInteger(var i) -> "the integer " + i;
///         case TomlTable table -> "a table of " + table.entries().size() + " entries";
///         default -> "something else";
///     };
/// }
/// ```
///
/// @since 0.1.0
public sealed interface TomlValue
        permits TomlTable,
                TomlArray,
                TomlString,
                TomlInteger,
                TomlFloat,
                TomlBoolean,
                TomlOffsetDateTime,
                TomlLocalDateTime,
                TomlLocalDate,
                TomlLocalTime {}
