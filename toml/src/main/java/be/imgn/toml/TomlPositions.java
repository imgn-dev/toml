package be.imgn.toml;

import static java.util.Objects.checkIndex;
import static java.util.Objects.requireNonNull;

import java.util.IdentityHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Where the keys and values of a parsed document are written, to report a problem with one at its line and column.
 *
 * <pre>{@code
 * var document = Toml.parse(file);
 * var save = document.root().getTable("commands.save");
 * if (!save.entries().containsKey("command")) {
 *     var position = document.positions().key(document.root().getTable("commands"), "save");
 *     report(position.line(), position.column(), "[commands.save] names no command");
 * }
 * }</pre>
 *
 * <p>A position is looked up by the table or array that holds the key or the element, which must be the very instance
 * the parse returned: positions compare tables and arrays by identity, as equal tables can be written in several
 * places. A copy of a table, such as one built from it with {@link TomlTable#toBuilder()}, has no positions.
 *
 * <p>The key is a key of that table, never a dotted path: the position of {@code port} in {@code [server]} is
 * {@code key(root.getTable("server"), "port")}.
 *
 * @since 0.1.0
 */
public final class TomlPositions {

    private final TomlTable root;
    private final TableShape shape;
    // Built on the first lookup, so that parsing does not pay for hashing every table and array by identity.
    private @Nullable Index index;

    /**
     * Creates the positions of a document.
     *
     * @param root the root table of the document
     * @param shape where the entries of {@code root}, and of the tables and arrays in it, are written
     */
    TomlPositions(TomlTable root, TableShape shape) {
        this.root = root;
        this.shape = shape;
    }

    /**
     * Returns where a key of a table is written.
     *
     * <p>That is the key itself, or its own part of a dotted key: the position of {@code save} in
     * {@code [commands.save]} is that of {@code save}. A table that is defined in several places, as a table implicitly
     * defined by {@code [a.b]} and then by {@code [a]}, is at its header. An array of tables is at its first header.
     *
     * @param table a table of the document
     * @param key a key of {@code table}
     * @return the position of {@code key}
     * @throws IllegalArgumentException if {@code table} is not a table of the document, or has no key {@code key}
     * @throws NullPointerException if an argument is null
     */
    public TomlPosition key(TomlTable table, String key) {
        return unpack(spot(table, key).keyPosition());
    }

    /**
     * Returns where the value of a key of a table starts.
     *
     * <p>That is the first character of the value, which is the opening bracket or brace of an array or an inline
     * table. For a table defined by a header, it is the header's opening bracket, and for an array of tables, its first
     * header's. A table that only exists as the parent of others, as {@code a} for {@code [a.b]} or {@code a.b = 1}, is
     * at its key.
     *
     * @param table a table of the document
     * @param key a key of {@code table}
     * @return the position of the value of {@code key}
     * @throws IllegalArgumentException if {@code table} is not a table of the document, or has no key {@code key}
     * @throws NullPointerException if an argument is null
     */
    public TomlPosition value(TomlTable table, String key) {
        return unpack(spot(table, key).valuePosition());
    }

    /**
     * Returns where an element of an array starts.
     *
     * <p>That is the first character of the element, as for {@link #value(TomlTable, String)}. The element of an array
     * of tables is at the opening bracket of its header.
     *
     * @param array an array of the document
     * @param index the index of an element of {@code array}
     * @return the position of the element
     * @throws IllegalArgumentException if {@code array} is not an array of the document
     * @throws IndexOutOfBoundsException if {@code index} is not an index of {@code array}
     * @throws NullPointerException if {@code array} is null
     */
    public TomlPosition element(TomlArray array, int index) {
        requireNonNull(array, "array");
        var elements = index().arrays.get(array);
        if (elements == null) {
            throw new IllegalArgumentException("the array is not an array of this document");
        }
        return unpack(elements.position(checkIndex(index, elements.size())));
    }

    private Spot spot(TomlTable table, String key) {
        requireNonNull(table, "table");
        requireNonNull(key, "key");
        var entries = index().tables.get(table);
        if (entries == null) {
            throw new IllegalArgumentException("the table is not a table of this document");
        }
        var spot = entries.spots().get(key);
        if (spot == null) {
            throw new IllegalArgumentException("the table has no key '" + key + "'");
        }
        return spot;
    }

    private synchronized Index index() {
        if (index == null) {
            index = new Index(root, shape);
        }
        return index;
    }

    // A document has many positions, and few are looked up: they are kept as longs, and become positions on lookup.

    static long pack(int line, int column) {
        return (long) line << Integer.SIZE | column;
    }

    static TomlPosition unpack(long position) {
        return new TomlPosition((int) (position >>> Integer.SIZE), (int) position);
    }

    /** The shapes of the tables and arrays of a document, by identity. */
    private static final class Index {

        private final IdentityHashMap<TomlTable, TableShape> tables = new IdentityHashMap<>();
        private final IdentityHashMap<TomlArray, ArrayShape> arrays = new IdentityHashMap<>();

        Index(TomlTable root, TableShape shape) {
            add(root, shape);
        }

        private void add(TomlTable table, TableShape shape) {
            tables.put(table, shape);
            var spots = shape.spots();
            table.entries()
                    .forEach((key, value) ->
                            add(value, requireNonNull(spots.get(key)).shape()));
        }

        private void add(TomlValue value, @Nullable Shape shape) {
            if (value instanceof TomlTable table && shape instanceof TableShape tableShape) {
                add(table, tableShape);
            } else if (value instanceof TomlArray array && shape instanceof ArrayShape arrayShape) {
                arrays.put(array, arrayShape);
                for (var i = 0; i < arrayShape.size(); i++) {
                    add(array.values().get(i), arrayShape.elementShape(i));
                }
            }
        }
    }

    /**
     * Where the parts of a table or an array are written, which mirrors the table or the array: the parser records it
     * as it reads them, and the positions only look it up by identity once they are asked for one.
     */
    sealed interface Shape permits TableShape, ArrayShape {}

    /** Where the entries of a table are written. */
    non-sealed interface TableShape extends Shape {

        Map<String, ? extends Spot> spots();
    }

    /** Where the elements of an array are written. */
    non-sealed interface ArrayShape extends Shape {

        int size();

        long position(int index);

        @Nullable
        Shape elementShape(int index);
    }

    /** Where a key and its value are written, as {@link #pack(int, int)} packs them, and the shape of the value. */
    interface Spot {

        long keyPosition();

        long valuePosition();

        @Nullable
        Shape shape();
    }
}
