package be.imgn.toml;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import be.imgn.gaia.core.io.ByteSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs the <a href="https://github.com/toml-lang/toml-test">toml-test</a> suite, as listed by its TOML 1.1.0 manifest.
 *
 * <p>Each valid document must parse to the value of its JSON file, and must be written as a document that parses to the
 * same value. Each invalid document must be rejected.
 */
class TomlTestSuiteTest {

    private static final Path TESTS = tests();

    static Stream<String> validDocuments() {
        return documents("valid/");
    }

    static Stream<String> invalidDocuments() {
        return documents("invalid/");
    }

    @ParameterizedTest
    @MethodSource("validDocuments")
    void parsesValidDocument(String document) throws IOException {
        var expected = ByteSource.of(TESTS.resolve(document.replaceFirst("\\.toml$", ".json")))
                .asCharSource(UTF_8)
                .read();

        assertThat(parse(document)).isEqualTo(TaggedJson.parse(expected));
    }

    @ParameterizedTest
    @MethodSource("validDocuments")
    void writesValidDocumentBack(String document) throws IOException {
        var table = parse(document);

        assertThat(Toml.parse(Toml.format(table))).isEqualTo(table);
    }

    @ParameterizedTest
    @MethodSource("invalidDocuments")
    void rejectsInvalidDocument(String document) {
        assertThatExceptionOfType(TomlParseException.class).isThrownBy(() -> parse(document));
    }

    private static TomlTable parse(String document) throws IOException {
        return Toml.parse(ByteSource.of(TESTS.resolve(document)));
    }

    private static Stream<String> documents(String directory) {
        try {
            var manifest = ByteSource.of(TESTS.resolve("files-toml-1.1.0"))
                    .asCharSource(UTF_8)
                    .readLines();
            return manifest.stream().filter(file -> file.startsWith(directory) && file.endsWith(".toml"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path tests() {
        try {
            var tests = TomlTestSuiteTest.class.getResource("/toml-test/tests");
            return Path.of(Objects.requireNonNull(tests, "toml-test is missing").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
