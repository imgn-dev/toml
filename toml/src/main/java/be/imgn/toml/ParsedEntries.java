package be.imgn.toml;

import java.io.Serial;
import java.util.LinkedHashMap;

/// The entries of a table the parser read, which [TomlTable] takes without copying or checking them: the parser
/// creates them for that table alone, never changes them afterwards, and has already checked their keys and
/// values.
final class ParsedEntries extends LinkedHashMap<String, TomlValue> {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final float LOAD_FACTOR = 0.75f;

    /// Creates entries that hold `size` keys without resizing.
    ParsedEntries(int size) {
        super((int) Math.ceil(size / (double) LOAD_FACTOR), LOAD_FACTOR);
    }
}
