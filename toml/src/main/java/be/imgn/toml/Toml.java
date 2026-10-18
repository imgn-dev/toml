package be.imgn.toml;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import be.imgn.gaia.core.io.AtomicFiles;
import be.imgn.gaia.core.io.ByteSink;
import be.imgn.gaia.core.io.ByteSource;
import be.imgn.gaia.core.io.CharSink;
import be.imgn.gaia.core.io.CharSource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.file.Path;

/**
 * Parses and writes <a href="https://toml.io/en/v1.1.0">TOML 1.1</a> documents.
 *
 * <pre>{@code
 * TomlTable config = Toml.parse(file);
 * int port = config.getInt("server.port");
 *
 * Toml.write(config, CharSink.of(file, StandardCharsets.UTF_8));
 * }</pre>
 *
 * <p>The parser accepts every valid TOML 1.1 document, and rejects every invalid one with a {@link TomlParseException}.
 * A document may start with a byte order mark, which is ignored.
 *
 * <p>The writer writes the entries of a table before its subtables, and keeps the order of each. It writes nothing but
 * the data: comments, blank lines and the choice between equivalent notations, such as a literal string or a dotted
 * key, are not kept from a parsed document. It writes newlines as {@code \n}, and only TOML 1.0 notation, so that TOML
 * 1.0 parsers can read what it writes too.
 *
 * @since 0.1.0
 */
public final class Toml {

    private Toml() {}

    /**
     * Parses a document.
     *
     * @param document the document
     * @return the root table of {@code document}
     * @throws TomlParseException if {@code document} is not valid TOML
     * @throws NullPointerException if {@code document} is null
     */
    public static TomlTable parse(String document) {
        requireNonNull(document, "document");
        return Parser.parse(document);
    }

    /**
     * Parses a document read from a source of characters.
     *
     * @param source the source of the document
     * @return the root table of the document
     * @throws IOException if reading {@code source} fails
     * @throws TomlParseException if the document is not valid TOML
     * @throws NullPointerException if {@code source} is null
     */
    public static TomlTable parse(CharSource source) throws IOException {
        requireNonNull(source, "source");
        return parse(source.read());
    }

    /**
     * Parses a document read from a source of bytes, which must be UTF-8, as TOML requires.
     *
     * @param source the source of the document
     * @return the root table of the document
     * @throws IOException if reading {@code source} fails
     * @throws TomlParseException if the document is not valid UTF-8, or not valid TOML
     * @throws NullPointerException if {@code source} is null
     */
    public static TomlTable parse(ByteSource source) throws IOException {
        requireNonNull(source, "source");
        return parse(decode(source.read()));
    }

    /**
     * Parses a document read from a file, which must be UTF-8, as TOML requires.
     *
     * @param file the file of the document
     * @return the root table of the document
     * @throws IOException if reading {@code file} fails
     * @throws TomlParseException if the document is not valid UTF-8, or not valid TOML
     * @throws NullPointerException if {@code file} is null
     */
    public static TomlTable parse(Path file) throws IOException {
        requireNonNull(file, "file");
        return parse(ByteSource.of(file));
    }

    /**
     * Parses a document, and binds it to a {@link TomlConfig} record.
     *
     * @param <T> the type of the record
     * @param document the document
     * @param type the type of the record
     * @return the record
     * @throws TomlParseException if {@code document} is not valid TOML
     * @throws TomlMappingException if the document does not bind to the record
     * @throws IllegalArgumentException if no {@link TomlBinder} is registered for {@code type}
     * @throws NullPointerException if an argument is null
     * @since 0.1.0
     */
    public static <T> T parse(String document, Class<T> type) {
        requireNonNull(document, "document");
        requireNonNull(type, "type");
        var binder = Binders.of(type);
        return type.cast(binder.bind(parse(document)));
    }

    /**
     * Parses a document read from a source of characters, and binds it to a {@link TomlConfig} record.
     *
     * @param <T> the type of the record
     * @param source the source of the document
     * @param type the type of the record
     * @return the record
     * @throws IOException if reading {@code source} fails
     * @throws TomlParseException if the document is not valid TOML
     * @throws TomlMappingException if the document does not bind to the record
     * @throws IllegalArgumentException if no {@link TomlBinder} is registered for {@code type}
     * @throws NullPointerException if an argument is null
     * @since 0.1.0
     */
    public static <T> T parse(CharSource source, Class<T> type) throws IOException {
        requireNonNull(source, "source");
        requireNonNull(type, "type");
        var binder = Binders.of(type);
        return type.cast(binder.bind(parse(source)));
    }

    /**
     * Parses a document read from a source of bytes, which must be UTF-8, as TOML requires, and binds it to a
     * {@link TomlConfig} record.
     *
     * @param <T> the type of the record
     * @param source the source of the document
     * @param type the type of the record
     * @return the record
     * @throws IOException if reading {@code source} fails
     * @throws TomlParseException if the document is not valid UTF-8, or not valid TOML
     * @throws TomlMappingException if the document does not bind to the record
     * @throws IllegalArgumentException if no {@link TomlBinder} is registered for {@code type}
     * @throws NullPointerException if an argument is null
     * @since 0.1.0
     */
    public static <T> T parse(ByteSource source, Class<T> type) throws IOException {
        requireNonNull(source, "source");
        requireNonNull(type, "type");
        var binder = Binders.of(type);
        return type.cast(binder.bind(parse(source)));
    }

    /**
     * Parses a document read from a file, which must be UTF-8, as TOML requires, and binds it to a {@link TomlConfig}
     * record.
     *
     * @param <T> the type of the record
     * @param file the file of the document
     * @param type the type of the record
     * @return the record
     * @throws IOException if reading {@code file} fails
     * @throws TomlParseException if the document is not valid UTF-8, or not valid TOML
     * @throws TomlMappingException if the document does not bind to the record
     * @throws IllegalArgumentException if no {@link TomlBinder} is registered for {@code type}
     * @throws NullPointerException if an argument is null
     */
    public static <T> T parse(Path file, Class<T> type) throws IOException {
        requireNonNull(file, "file");
        requireNonNull(type, "type");
        return parse(ByteSource.of(file), type);
    }

    /**
     * Formats a table as a document.
     *
     * @param table the root table of the document
     * @return the document
     * @throws NullPointerException if {@code table} is null
     */
    public static String format(TomlTable table) {
        requireNonNull(table, "table");
        return Emitter.format(table);
    }

    /**
     * Writes a table as a document to a sink of characters.
     *
     * @param table the root table of the document
     * @param sink where to write the document
     * @throws IOException if writing to {@code sink} fails
     * @throws NullPointerException if {@code table} or {@code sink} is null
     */
    public static void write(TomlTable table, CharSink sink) throws IOException {
        requireNonNull(table, "table");
        requireNonNull(sink, "sink");
        try (var out = sink.openBufferedStream()) {
            Emitter.write(table, out);
        }
    }

    /**
     * Writes a table as a UTF-8 document to a sink of bytes.
     *
     * @param table the root table of the document
     * @param sink where to write the document
     * @throws IOException if writing to {@code sink} fails
     * @throws NullPointerException if {@code table} or {@code sink} is null
     */
    public static void write(TomlTable table, ByteSink sink) throws IOException {
        requireNonNull(table, "table");
        requireNonNull(sink, "sink");
        write(table, sink.asCharSink(UTF_8));
    }

    /**
     * Writes a table as a UTF-8 document to a file, atomically: readers, or the next run after a crash, see either the
     * previous content of the file or the whole document, never a part of it. The file keeps its permissions.
     *
     * @param table the root table of the document
     * @param file the file to create or replace
     * @throws IOException if writing the file fails, which leaves it as it was
     * @throws NullPointerException if {@code table} or {@code file} is null
     * @see AtomicFiles#write(Path, AtomicFiles.Output)
     */
    public static void writeAtomically(TomlTable table, Path file) throws IOException {
        requireNonNull(table, "table");
        requireNonNull(file, "file");
        AtomicFiles.write(file, stream -> {
            try (var out = new BufferedWriter(new OutputStreamWriter(stream, UTF_8))) {
                Emitter.write(table, out);
            }
        });
    }

    private static String decode(byte[] bytes) {
        // Decoding into a buffer, rather than with new String, tells where the malformed input is.
        var decoded = CharBuffer.allocate(bytes.length);
        var result = UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes), decoded, true);
        decoded.flip();
        if (result.isError()) {
            var lineStart = decoded.toString().lastIndexOf('\n') + 1;
            var line = (int) decoded.chars().filter(c -> c == '\n').count() + 1;
            throw new TomlParseException("invalid UTF-8", line, decoded.length() - lineStart + 1);
        }
        return decoded.toString();
    }
}
