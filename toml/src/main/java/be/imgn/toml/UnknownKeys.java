package be.imgn.toml;

/**
 * What binding does with a key of a table that no component of the record takes.
 *
 * @since 0.1.0
 */
public enum UnknownKeys {

    /** The key is an error, reported as a {@link TomlMappingException}. */
    REJECT,

    /** The key is ignored, so that a document written for another version of the records still binds. */
    IGNORE
}
