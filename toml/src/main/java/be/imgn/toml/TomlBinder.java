package be.imgn.toml;

/**
 * Binds tables to a record. The annotation processor generates one for each {@link TomlConfig} record, and registers it
 * as a service, which {@link Toml#parse(String, Class)} loads with {@link java.util.ServiceLoader}.
 *
 * @param <T> the type of the record
 * @since 0.1.0
 */
public interface TomlBinder<T> {

    /**
     * Returns the type of the record.
     *
     * @return the type of the record
     */
    Class<T> type();

    /**
     * Binds a table to a record.
     *
     * @param table the table
     * @return the record
     * @throws TomlMappingException if {@code table} does not bind to the record
     */
    T bind(TomlTable table);
}
