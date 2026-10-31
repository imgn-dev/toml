package be.imgn.toml.processor;

import static be.imgn.gaia.processing.testing.CompilationAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import be.imgn.toml.Toml;
import be.imgn.toml.TomlMappingException;
import be.imgn.toml.UnknownKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.assertj.core.api.ThrowableAssertAlternative;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Binds documents through the code the processor generates, compiled and loaded as an application would. */
class BindingTest {

    private static final String FULL = """
            title = "Inventory service"
            started = 2026-10-18T09:30:00+02:00

            [server]
            host = "0.0.0.0"
            port = 8080
            log-level = "warn"
            tls.enabled = true
            tls.cert = "/etc/inventory/cert.pem"

            [database]
            url = "jdbc:postgresql://db:5432/inventory"
            pool = { min = 2, max = 16 }
            timeout = "2.5s"
            load-factor = 1

            [[endpoints]]
            path = "/items"
            methods = ["get", "post"]

            [[endpoints]]
            path = "/items/{id}"
            methods = ["get", "put", "delete"]
            auth = false

            [maintenance]
            window = 03:00:00
            "cron.expression" = "0 3 * * SUN"

            [weights]
            b = 2
            a = 1
            """;

    private static final String WRITTEN = """
            title = "Inventory service"
            started = 2026-10-18T09:30:00+02:00

            [server]
            host = "0.0.0.0"
            port = 8080
            log-level = "warn"

            [server.tls]
            enabled = true
            cert = "/etc/inventory/cert.pem"

            [database]
            url = "jdbc:postgresql://db:5432/inventory"
            timeout = "PT2.5S"
            load-factor = 1.0

            [database.pool]
            min = 2
            max = 16

            [[endpoints]]
            path = "/items"
            methods = ["get", "post"]

            [[endpoints]]
            path = "/items/{id}"
            methods = ["get", "put", "delete"]
            auth = false

            [maintenance]
            window = 03:00:00
            "cron.expression" = "0 3 * * SUN"

            [weights]
            b = 2
            a = 1
            """;

    private static final String MINIMAL = """
            title = "t"
            started = 2026-10-18T09:30:00Z
            endpoints = [{ path = "/", methods = ["get"] }]
            weights = {}

            [server]
            host = "localhost"
            port = 8080
            log-level = "info"
            tls = { enabled = false, cert = "c" }

            [database]
            url = "u"
            pool = { min = 1, max = 2 }
            timeout = "PT1S"
            load-factor = 0.5

            [maintenance]
            window = 03:00:00
            "cron.expression" = "c"
            """;

    private static Class<?> appConfig = Object.class;
    private static Class<?> limits = Object.class;
    private static Class<?> tree = Object.class;

    @BeforeAll
    static void compile(@TempDir Path directory) throws ClassNotFoundException {
        var compilation = Compiler.compile("example.AppConfig", """
                package example;

                import be.imgn.toml.TomlConfig;
                import be.imgn.toml.TomlKey;
                import be.imgn.toml.TomlTable;
                import java.net.URI;
                import java.nio.file.Path;
                import java.time.Duration;
                import java.time.LocalTime;
                import java.time.OffsetDateTime;
                import java.util.List;
                import java.util.Map;
                import java.util.Set;
                import org.jspecify.annotations.Nullable;

                @TomlConfig
                public record AppConfig(
                        String title,
                        OffsetDateTime started,
                        Server server,
                        Database database,
                        List<Endpoint> endpoints,
                        Maintenance maintenance,
                        Map<String, Integer> weights,
                        @Nullable TomlTable extra) {

                    public record Server(String host, int port, Tls tls, Level logLevel) {}

                    public record Tls(boolean enabled, Path cert) {}

                    public record Database(URI url, Pool pool, Duration timeout, double loadFactor) {}

                    public record Pool(int min, int max) {
                        public Pool {
                            if (min > max) {
                                throw new IllegalArgumentException("min must not exceed max");
                            }
                        }
                    }

                    public record Endpoint(String path, Set<Method> methods, @Nullable Boolean auth) {}

                    public record Maintenance(LocalTime window, @TomlKey("cron.expression") String cron) {}

                    public enum Level {
                        DEBUG,
                        INFO,
                        @TomlKey("warn") WARNING
                    }

                    public enum Method {
                        GET,
                        POST,
                        PUT,
                        DELETE
                    }
                }
                """, "example.Limits", """
                package example;

                import be.imgn.toml.TomlConfig;
                import java.util.List;

                @TomlConfig
                record Limits(byte retries, short burst, long total, float ratio, List<List<String>> groups) {}
                """, "example.Tree", """
                package example;

                import be.imgn.toml.TomlConfig;
                import java.util.List;

                @TomlConfig
                public record Tree(String name, List<Tree> children) {}
                """);
        assertThat(compilation).succeeded().hadNoWarnings();
        var loader = Compiler.load(compilation, directory);
        appConfig = loader.loadClass("example.AppConfig");
        limits = loader.loadClass("example.Limits");
        tree = loader.loadClass("example.Tree");
    }

    @Test
    void bindsDocument() {
        var config = Toml.parse(FULL, appConfig);
        assertThat(config)
                .hasToString("AppConfig[title=Inventory service, started=2026-10-18T09:30+02:00,"
                        + " server=Server[host=0.0.0.0, port=8080, tls=Tls[enabled=true, cert=/etc/inventory/cert.pem],"
                        + " logLevel=WARNING],"
                        + " database=Database[url=jdbc:postgresql://db:5432/inventory, pool=Pool[min=2, max=16],"
                        + " timeout=PT2.5S, loadFactor=1.0],"
                        + " endpoints=[Endpoint[path=/items, methods=[GET, POST], auth=null],"
                        + " Endpoint[path=/items/{id}, methods=[GET, PUT, DELETE], auth=false]],"
                        + " maintenance=Maintenance[window=03:00, cron=0 3 * * SUN], weights={b=2, a=1}, extra=null]");
    }

    @Test
    void bindsFile(@TempDir Path directory) throws IOException {
        var file = Files.writeString(directory.resolve("config.toml"), MINIMAL);

        assertThat(Toml.parse(file, appConfig)).isEqualTo(Toml.parse(MINIMAL, appConfig));
    }

    @Test
    void bindsTomlValue() {
        var config = Toml.parse(MINIMAL + "[extra]\nanything = [1, \"two\"]\n", appConfig);
        assertThat(config.toString())
                .endsWith("extra=TomlTable[entries={anything=TomlArray[values=[TomlInteger[value=1],"
                        + " TomlString[value=two]]]}]]");
    }

    @Test
    void bindsNumbersAndNestedLists() {
        var config = Toml.parse("""
                retries = 3
                burst = 1000
                total = 9_000_000_000
                ratio = 0.5
                groups = [["a", "b"], [], ["c"]]
                """, limits);
        assertThat(config)
                .hasToString("Limits[retries=3, burst=1000, total=9000000000, ratio=0.5, groups=[[a, b], [], [c]]]");
    }

    @Test
    void bindsRecursiveRecord() {
        var config = Toml.parse("""
                name = "root"
                children = [{ name = "a", children = [{ name = "b", children = [] }] }]
                """, tree);
        assertThat(config)
                .hasToString("Tree[name=root, children=[Tree[name=a, children=[Tree[name=b, children=[]]]]]]");
    }

    @Test
    void rejectsUnknownKey() {
        assertThatMappingException(MINIMAL.replace("port = 8080", "port = 8080\nprot = 8081"), appConfig)
                .withMessage("server: unknown key prot");
    }

    @Test
    void rejectsMissingKey() {
        assertThatMappingException(MINIMAL.replace("host = \"localhost\"\n", ""), appConfig)
                .withMessage("server.host: missing");
    }

    @Test
    void rejectsWrongTypeInArray() {
        assertThatMappingException(MINIMAL.replace("methods = [\"get\"]", "methods = [\"get\", 1]"), appConfig)
                .withMessage("endpoints[0].methods[1]: expected a string, found an integer");
    }

    @Test
    void rejectsUnknownEnumString() {
        assertThatMappingException(MINIMAL.replace("log-level = \"info\"", "log-level = \"warning\""), appConfig)
                .withMessage("server.log-level: expected one of \"debug\", \"info\", \"warn\", found \"warning\"");
    }

    @Test
    void rejectsDuplicateSetElement() {
        assertThatMappingException(MINIMAL.replace("methods = [\"get\"]", "methods = [\"get\", \"get\"]"), appConfig)
                .withMessage("endpoints[0].methods[1]: duplicate element");
    }

    @Test
    void reportsRecordThatRejectsItsValues() {
        assertThatMappingException(MINIMAL.replace("min = 1, max = 2", "min = 3, max = 2"), appConfig)
                .withMessage("database.pool: min must not exceed max")
                .withCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsIntegersOutOfRange() {
        assertThatMappingException("retries = 300\nburst = 1\ntotal = 1\nratio = 1\ngroups = []\n", limits)
                .withMessage("retries: expected an integer from -128 to 127, found 300");
        assertThatMappingException("retries = 1\nburst = 1\ntotal = 1\nratio = 16777217\ngroups = []\n", limits)
                .withMessage("ratio: the integer 16777217 has no exact float value");
    }

    @Test
    void bindsTableInMemory() {
        assertThat(Toml.bind(Toml.parse(MINIMAL).root(), appConfig)).isEqualTo(Toml.parse(MINIMAL, appConfig));
    }

    @Test
    void ignoresUnknownKeysOfEveryRecord() {
        var document = "version = 2\n"
                + MINIMAL.replace("port = 8080", "port = 8080\nprot = 8081")
                        .replace("min = 1, max = 2", "min = 1, max = 2, idle = 0")
                        .replace("methods = [\"get\"]", "methods = [\"get\"], cors = true");
        var table = Toml.parse(document).root();

        assertThat(Toml.bind(table, appConfig, UnknownKeys.IGNORE)).isEqualTo(Toml.parse(MINIMAL, appConfig));
        assertThatExceptionOfType(TomlMappingException.class)
                .isThrownBy(() -> Toml.bind(table, appConfig, UnknownKeys.REJECT))
                .withMessage("unknown key version");
    }

    @Test
    void writesRecordThatBindsBackToAnEqualRecord() {
        var config = (Record) Toml.parse(FULL, appConfig);

        var table = Toml.toTable(config);

        assertThat(Toml.bind(table, appConfig)).isEqualTo(config);
    }

    @Test
    void writesValuesInTheFormsTheyBindFrom() {
        var table = Toml.toTable((Record) Toml.parse(FULL, appConfig));

        assertThat(Toml.format(table)).isEqualTo(WRITTEN);
    }

    @Test
    void writesNumbersAndNestedLists() {
        var config = (Record) Toml.parse("""
                retries = -3
                burst = 1000
                total = 9_000_000_000
                ratio = nan
                groups = [["a", "b"], [], ["c"]]
                """, limits);

        var table = Toml.toTable(config);

        assertThat(Toml.format(table)).isEqualTo("""
                retries = -3
                burst = 1000
                total = 9000000000
                ratio = nan
                groups = [["a", "b"], [], ["c"]]
                """);
        assertThat(Toml.bind(table, limits)).isEqualTo(config);
    }

    @Test
    void writesRecursiveRecord() {
        var config = (Record) Toml.parse("""
                name = "root"
                children = [{ name = "a", children = [{ name = "b", children = [] }] }]
                """, tree);

        assertThat(Toml.bind(Toml.toTable(config), tree)).isEqualTo(config);
    }

    private static ThrowableAssertAlternative<TomlMappingException> assertThatMappingException(
            String document, Class<?> type) {
        return assertThatExceptionOfType(TomlMappingException.class).isThrownBy(() -> Toml.parse(document, type));
    }
}
