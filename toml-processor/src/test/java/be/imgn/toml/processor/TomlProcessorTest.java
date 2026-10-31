package be.imgn.toml.processor;

import static be.imgn.gaia.processing.testing.CompilationAssert.assertThat;
import static be.imgn.gaia.processing.testing.ProcessorAssert.assertThat;

import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;

class TomlProcessorTest {

    @Test
    void isRegistered() {
        assertThat(TomlProcessor.class).isRegistered();
    }

    @Test
    void generatesBinder() {
        var compilation = Compiler.compile("example.Server", """
                package example;

                import be.imgn.toml.TomlConfig;
                import be.imgn.toml.TomlKey;
                import org.jspecify.annotations.Nullable;

                @TomlConfig
                record Server(String host, @Nullable Integer port, @TomlKey("log") Level level, Tls tls) {

                    record Tls(boolean enabled) {}

                    enum Level {
                        DEBUG,
                        READ_ONLY
                    }
                }
                """);
        assertThat(compilation).succeeded().hadNoWarnings();
        assertThat(compilation)
                .generatedSourceFile("example.$Server$$TomlBinder")
                .isEqualTo("""
                package example;

                import be.imgn.toml.TomlBinder;
                import be.imgn.toml.TomlBindings;
                import be.imgn.toml.TomlBoolean;
                import be.imgn.toml.TomlInteger;
                import be.imgn.toml.TomlString;
                import be.imgn.toml.TomlTable;
                import be.imgn.toml.TomlValue;
                import be.imgn.toml.UnknownKeys;
                import javax.annotation.processing.Generated;

                /** Binds tables to {@code Server}, and writes it to tables. */
                @Generated("be.imgn.toml.processor.TomlProcessor")
                public final class $Server$$TomlBinder implements TomlBinder<Server> {

                    /** Creates the binder; the service loader does. */
                    public $Server$$TomlBinder() {}

                    @Override
                    public Class<Server> type() {
                        return Server.class;
                    }

                    @Override
                    public Server bind(TomlTable table, UnknownKeys unknownKeys) {
                        return server(table, new TomlBindings.Context("", unknownKeys));
                    }

                    @Override
                    public TomlTable toTable(Server value) {
                        return writeServer(value);
                    }

                    private static Server server(TomlValue value, TomlBindings.Context context) {
                        var table = TomlBindings.table(value, context, "host", "port", "log", "tls");
                        var host = TomlBindings.required(table, context, "host", TomlBindings::asString);
                        var port = TomlBindings.optional(table, context, "port", TomlBindings::asInt);
                        var level = TomlBindings.required(table, context, "log", $Server$$TomlBinder::level);
                        var tls = TomlBindings.required(table, context, "tls", $Server$$TomlBinder::tls);
                        try {
                            return new Server(host, port, level, tls);
                        } catch (RuntimeException e) {
                            throw TomlBindings.rejected(context, e);
                        }
                    }

                    private static TomlTable writeServer(Server value) {
                        var builder = TomlTable.builder();
                        TomlBindings.put(builder, "host", value.host(), e1 -> new TomlString(e1));
                        TomlBindings.putIfNotNull(builder, "port", value.port(), e1 -> new TomlInteger(e1));
                        TomlBindings.put(builder, "log", value.level(), e1 -> writeLevel(e1));
                        TomlBindings.put(builder, "tls", value.tls(), e1 -> writeTls(e1));
                        return builder.build();
                    }

                    private static Server.Tls tls(TomlValue value, TomlBindings.Context context) {
                        var table = TomlBindings.table(value, context, "enabled");
                        var enabled = TomlBindings.required(table, context, "enabled", TomlBindings::asBoolean);
                        try {
                            return new Server.Tls(enabled);
                        } catch (RuntimeException e) {
                            throw TomlBindings.rejected(context, e);
                        }
                    }

                    private static TomlTable writeTls(Server.Tls value) {
                        var builder = TomlTable.builder();
                        TomlBindings.put(builder, "enabled", value.enabled(), e1 -> new TomlBoolean(e1));
                        return builder.build();
                    }

                    private static Server.Level level(TomlValue value, TomlBindings.Context context) {
                        return switch (TomlBindings.asString(value, context)) {
                            case "debug" -> Server.Level.DEBUG;
                            case "read-only" -> Server.Level.READ_ONLY;
                            default -> throw TomlBindings.notOneOf(value, context, "debug", "read-only");
                        };
                    }

                    private static TomlString writeLevel(Server.Level value) {
                        return new TomlString(switch (value) {
                            case DEBUG -> "debug";
                            case READ_ONLY -> "read-only";
                        });
                    }
                }
                """);
        assertThat(compilation)
                .generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/services/be.imgn.toml.TomlBinder")
                .isEqualTo("example.$Server$$TomlBinder\n");
    }

    @Test
    void rejectsClass() {
        var compilation = Compiler.compile("example.Config", """
                package example;

                @be.imgn.toml.TomlConfig
                class Config {}
                """);
        assertThat(compilation)
                .hadErrorContaining("@TomlConfig must annotate a record")
                .inFile("example/Config.java")
                .onLine(3);
    }

    @Test
    void rejectsGenericRecord() {
        var compilation = Compiler.compile("example.Config", """
                package example;

                @be.imgn.toml.TomlConfig
                record Config<T>(T value) {}
                """);
        assertThat(compilation)
                .hadErrorContaining("A @TomlConfig record cannot be generic")
                .onLine(3);
    }

    @Test
    void rejectsPrivateRecord() {
        var compilation = Compiler.compile("example.Outer", """
                package example;

                class Outer {
                    @be.imgn.toml.TomlConfig
                    private record Config(String name) {}
                }
                """);
        assertThat(compilation).hadErrorContaining("A @TomlConfig record must be accessible from its package");
    }

    @Test
    void rejectsUnsupportedTypes() {
        var compilation = Compiler.compile("example.Config", """
                package example;

                import java.util.List;
                import java.util.Map;
                import java.util.Optional;

                @be.imgn.toml.TomlConfig
                record Config(
                        Optional<String> optional,
                        char letter,
                        Object object,
                        @SuppressWarnings("rawtypes") List raw,
                        Map<Integer, String> numbers,
                        String[] array,
                        List<? extends String> wildcard,
                        Pair<String> pair) {

                    record Pair<T>(T first, T second) {}
                }
                """);
        assertThat(compilation).failed().hadErrorCount(8);
        assertThat(compilation)
                .hadErrorContaining("Cannot bind the type java.util.Optional<java.lang.String>: see @TomlConfig")
                .onLine(9);
        assertThat(compilation).hadErrorContaining("Cannot bind the type char").onLine(10);
        assertThat(compilation)
                .hadErrorContaining("Cannot bind the type ? extends java.lang.String")
                .onLine(15);
        assertThat(compilation)
                .hadErrorContaining("Cannot bind the type example.Config.Pair<java.lang.String>")
                .onLine(16);
    }

    @Test
    void rejectsNullablePrimitive() {
        var compilation = Compiler.compile("example.Config", """
                package example;

                import org.jspecify.annotations.Nullable;

                @be.imgn.toml.TomlConfig
                record Config(@Nullable int port) {}
                """);
        assertThat(compilation)
                .hadErrorContaining("A component of a primitive type cannot be @Nullable: use its boxed type")
                .onLine(6);
    }

    @Test
    void rejectsDuplicateKeys() {
        var compilation = Compiler.compile("example.Config", """
                package example;

                import be.imgn.toml.TomlKey;

                @be.imgn.toml.TomlConfig
                record Config(@TomlKey("max-size") int limit, int maxSize, Level level) {

                    enum Level {
                        @TomlKey("low") DEBUG,
                        LOW
                    }
                }
                """);
        assertThat(compilation).failed().hadErrorCount(2);
        assertThat(compilation).hadErrorContaining("Another component already has the key \"max-size\"");
        assertThat(compilation).hadErrorContaining("Another constant already has the key \"low\"");
    }

    @Test
    void rejectsNamesWithoutKey() {
        var compilation = Compiler.compile("example.Config", """
                package example;

                import be.imgn.toml.TomlKey;

                @be.imgn.toml.TomlConfig
                record Config(int max_size, @TomlKey("min") int min_size, Mode mode) {

                    enum Mode {
                        readOnly,
                        @TomlKey("write") write_only
                    }
                }
                """);
        assertThat(compilation).failed().hadErrorCount(2);
        assertThat(compilation)
                .hadErrorContaining("Cannot derive a key from the name max_size: give it one with @TomlKey")
                .onLine(6);
        assertThat(compilation)
                .hadErrorContaining("Cannot derive a key from the name readOnly: give it one with @TomlKey")
                .onLine(9);
    }

    @Test
    void rejectsInaccessibleRecord() {
        var compilation = Compiler.compile("other.Base", """
                package other;

                public class Base {
                    protected record Hidden(String name) {}
                }
                """, "example.Sub", """
                package example;

                public class Sub extends other.Base {
                    @be.imgn.toml.TomlConfig
                    public record Config(Hidden hidden) {}
                }
                """);
        assertThat(compilation)
                .hadErrorContaining("Cannot bind other.Base.Hidden: it is not accessible from the package example")
                .inFile("example/Sub.java")
                .onLine(5);
    }

    @Test
    void derivesKeysInKebabCase() {
        var compilation = Compiler.compile("example.Config", """
                package example;

                @be.imgn.toml.TomlConfig
                record Config(String httpURLPath, String ipv4Address, String utf8, Mode mode) {

                    enum Mode {
                        READ_ONLY,
                        HTTP2
                    }
                }
                """);
        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Config$$TomlBinder")
                .contains("TomlBindings.table(value, context, \"http-url-path\", \"ipv4-address\", \"utf8\", \"mode\")")
                .contains("case \"read-only\" -> Config.Mode.READ_ONLY;")
                .contains("case \"http2\" -> Config.Mode.HTTP2;");
    }

    @Test
    void compilesNamesThatTheGeneratedCodeUses() {
        var compilation = Compiler.compile("example.Config", """
                package example;

                import java.util.List;

                @be.imgn.toml.TomlConfig
                record Config(
                        String value,
                        String context,
                        String table,
                        String builder,
                        String e,
                        List<List<String>> e1,
                        WriteConfig writeConfig) {

                    record WriteConfig(String name) {}
                }
                """);
        assertThat(compilation).succeeded().hadNoWarnings();
    }
}
