package be.imgn.toml;

import static be.imgn.gaia.core.base.Preconditions.checkArgument;

/**
 * A position in a document: a line and a column.
 *
 * <p>A line ends with {@code \n} or {@code \r\n}. The byte order mark a document may start with is not counted: the
 * first character after it is at line 1, column 1.
 *
 * @param line the line, from 1
 * @param column the column, from 1, counted in UTF-16 code units, as in a {@link String}
 * @since 0.1.0
 */
public record TomlPosition(int line, int column) {

    /**
     * Creates a position.
     *
     * @throws IllegalArgumentException if {@code line} or {@code column} is less than 1
     */
    public TomlPosition {
        checkArgument(line >= 1, "the line %s is less than 1", line);
        checkArgument(column >= 1, "the column %s is less than 1", column);
    }
}
