package be.imgn.toml;

import static java.util.Objects.requireNonNull;

/**
 * A parsed document: its root table, and where its keys and values are written.
 *
 * <p>Two documents are equal when they have the same root table and the same positions. Positions are equal only to
 * themselves, so two parses of a text are not equal documents, but their root tables are equal.
 *
 * @param root the root table
 * @param positions where the keys and values of {@code root}, and of the tables and arrays in it, are written
 * @since 0.1.0
 */
public record TomlDocument(TomlTable root, TomlPositions positions) {

    /**
     * Creates a document.
     *
     * @throws NullPointerException if {@code root} or {@code positions} is null
     */
    public TomlDocument {
        requireNonNull(root, "root");
        requireNonNull(positions, "positions");
    }
}
