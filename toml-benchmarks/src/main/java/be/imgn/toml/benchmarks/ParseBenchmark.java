package be.imgn.toml.benchmarks;

import be.imgn.gaia.core.io.ByteSource;
import be.imgn.toml.Toml;
import be.imgn.toml.TomlDocument;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.tomlj.TomlParseResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * Parses a document into a tree, from a {@code String} and from UTF-8 bytes. Every library builds its whole tree, so
 * that none of them reads any value afterwards.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ParseBenchmark {

    private static final TomlMapper JACKSON = new TomlMapper();

    /** The document, as {@link Documents#get(String)} names it. */
    @Param({"small", "medium", "large", "numbers", "strings"})
    public String document = "";

    private String text = "";
    private byte[] bytes = new byte[0];

    /** Generates the document. */
    @Setup
    public void setUp() {
        text = Documents.get(document);
        bytes = text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Parses the document from a {@code String}.
     *
     * @return the document
     */
    @Benchmark
    public TomlDocument string() {
        return Toml.parse(text);
    }

    /**
     * Parses the document from UTF-8 bytes.
     *
     * @return the document
     * @throws IOException never, as the bytes are in memory
     */
    @Benchmark
    public TomlDocument bytes() throws IOException {
        return Toml.parse(ByteSource.wrap(bytes));
    }

    /**
     * Parses the document from a {@code String} into a tomlj tree.
     *
     * @return the tree, with the errors tomlj found
     */
    @Benchmark
    public TomlParseResult tomlj() {
        return org.tomlj.Toml.parse(text);
    }

    /**
     * Parses the document from a {@code String} into a Jackson tree.
     *
     * @return the tree
     */
    @Benchmark
    public JsonNode jackson() {
        return JACKSON.readTree(text);
    }
}
