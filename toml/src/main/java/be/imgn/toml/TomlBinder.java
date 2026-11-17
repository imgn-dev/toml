package be.imgn.toml;

/// Binds tables to a record, and converts the record back to a table. The annotation processor generates one
/// for each [TomlConfig] record, and registers it as a service, which [Toml#bind(TomlTable, Class)] and
/// [Toml#toTable(Record)] load with [java.util.ServiceLoader].
///
/// @param <T> the type of the record
/// @since 0.1.0
public interface TomlBinder<T> {

    /// Returns the type of the record.
    ///
    /// @return the type of the record
    Class<T> type();

    /// Binds a table to a record.
    ///
    /// @param table the table
    /// @param unknownKeys what to do with a key that no component takes
    /// @return the record
    /// @throws TomlMappingException if `table` does not bind to the record
    T bind(TomlTable table, UnknownKeys unknownKeys);

    /// Converts a record to a table, which binds back to an equal record.
    ///
    /// @param value the record
    /// @return the table
    /// @throws NullPointerException if a value in `value` that is not `@Nullable` is null
    /// @throws IllegalArgumentException if a string in `value` contains an unpaired surrogate
    TomlTable toTable(T value);
}
