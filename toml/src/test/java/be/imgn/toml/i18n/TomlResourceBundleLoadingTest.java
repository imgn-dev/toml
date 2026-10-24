package be.imgn.toml.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import be.imgn.toml.TomlParseException;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.module.ModuleFinder;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Loads bundles with {@link ResourceBundle#getBundle}, from an application compiled for the class path, and from one
 * compiled as a named module, whose resources the module encapsulates.
 */
class TomlResourceBundleLoadingTest {

    private static final String ROOT_BUNDLE = """
            title = "Inventory"
            days = ["Monday", "Tuesday"]

            [errors]
            not-found = "No item {0}"
            retries = 3
            """;

    private static final String FRENCH_BUNDLE = """
            title = "Inventaire"
            days = ["lundi", "mardi"]

            [errors]
            not-found = "Aucun article {0}"
            """;

    /** The modules the application compiles against: this one, and those it requires. */
    private static final List<String> MODULES =
            List.of("be.imgn.toml", "be.imgn.gaia.core", "org.jspecify", "com.google.errorprone.annotations");

    @TempDir
    Path directory;

    @Test
    void loadsBundlesFromClassPath() throws Exception {
        var classes = compile(
                Map.of(
                        "app/Main.java",
                        """
                        package app;

                        import be.imgn.toml.i18n.TomlResourceBundle;
                        import java.util.Locale;
                        import java.util.ResourceBundle;

                        public final class Main {
                            public static ResourceBundle bundle(String baseName, Locale locale) {
                                return ResourceBundle.getBundle(baseName, locale, TomlResourceBundle.control());
                            }
                        }
                        """,
                        "app/messages.toml",
                        ROOT_BUNDLE,
                        "app/messages_fr.toml",
                        FRENCH_BUNDLE,
                        "app/broken.toml",
                        "title ="),
                "--class-path",
                modulePath());
        var urls = new ArrayList<URL>();
        urls.add(url(classes));
        for (var module : MODULES) {
            urls.add(url(location(module)));
        }

        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            var bundle = loader.loadClass("app.Main").getMethod("bundle", String.class, Locale.class);

            var french = (ResourceBundle) bundle.invoke(null, "app.messages", Locale.FRANCE);

            assertThat(french.getLocale()).isEqualTo(Locale.FRENCH);
            assertThat(french.getString("title")).isEqualTo("Inventaire");
            assertThat(french.getStringArray("days")).containsExactly("lundi", "mardi");
            assertThat(french.getObject("errors.retries")).isEqualTo(3L);
            assertThat(((ResourceBundle) bundle.invoke(null, "app.messages", Locale.ROOT)).getString("title"))
                    .isEqualTo("Inventory");
            // The loader defines its own copy of the classes of be.imgn.toml.
            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(() -> bundle.invoke(null, "app.broken", Locale.ROOT))
                    .havingCause()
                    .isInstanceOf(MissingResourceException.class)
                    .havingCause()
                    .matches(cause -> cause.getClass().getName().equals(TomlParseException.class.getName()));
        }
    }

    @Test
    void loadsBundlesOfNamedModuleThroughProvider() throws Exception {
        var classes = compile(
                Map.of(
                        "module-info.java",
                        """
                        module app {
                            requires be.imgn.toml;
                            exports app;
                            uses app.internal.spi.MessagesProvider;
                            provides app.internal.spi.MessagesProvider with app.internal.TomlMessagesProvider;
                        }
                        """,
                        "app/Main.java",
                        """
                        package app;

                        import java.util.Locale;
                        import java.util.ResourceBundle;

                        public final class Main {
                            public static ResourceBundle bundle(Locale locale) {
                                return ResourceBundle.getBundle("app.internal.Messages", locale);
                            }
                        }
                        """,
                        "app/internal/spi/MessagesProvider.java",
                        """
                        package app.internal.spi;

                        import java.util.spi.ResourceBundleProvider;

                        public interface MessagesProvider extends ResourceBundleProvider {}
                        """,
                        "app/internal/TomlMessagesProvider.java",
                        """
                        package app.internal;

                        import app.internal.spi.MessagesProvider;
                        import be.imgn.toml.i18n.TomlResourceBundleProvider;

                        public final class TomlMessagesProvider extends TomlResourceBundleProvider
                                implements MessagesProvider {
                            public TomlMessagesProvider() {
                                super(TomlMessagesProvider.class.getModule()::getResourceAsStream);
                            }
                        }
                        """,
                        "app/internal/Messages.toml",
                        ROOT_BUNDLE,
                        "app/internal/Messages_fr.toml",
                        FRENCH_BUNDLE),
                "--module-path",
                modulePath());
        var boot = ModuleLayer.boot();
        var configuration = boot.configuration().resolve(ModuleFinder.of(classes), ModuleFinder.of(), Set.of("app"));
        var layer = boot.defineModulesWithOneLoader(configuration, ClassLoader.getPlatformClassLoader());
        var main = layer.findLoader("app").loadClass("app.Main");

        var bundle = (TomlResourceBundle) main.getMethod("bundle", Locale.class).invoke(null, Locale.FRANCE);

        // The module does not open app.internal, so only its own code can read the resources.
        assertThat(TomlResourceBundleLoadingTest.class.getModule().isNamed()).isTrue();
        assertThat(layer.findModule("app").orElseThrow().getResourceAsStream("app/internal/Messages.toml"))
                .isNull();
        assertThat(bundle.getLocale()).isEqualTo(Locale.FRENCH);
        assertThat(bundle.getString("title")).isEqualTo("Inventaire");
        assertThat(bundle.getStringArray("days")).containsExactly("lundi", "mardi");
        assertThat(bundle.getLong("errors.retries")).isEqualTo(3);
    }

    private Path compile(Map<String, String> files, String pathOption, String path) throws IOException {
        var sources = directory.resolve("sources");
        var classes = directory.resolve("classes");
        var arguments = new ArrayList<>(List.of("-d", classes.toString(), "-proc:none", pathOption, path));
        for (var file : files.entrySet()) {
            var target = (file.getKey().endsWith(".java") ? sources : classes).resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
            if (file.getKey().endsWith(".java")) {
                arguments.add(target.toString());
            }
        }
        var javac = ToolProvider.findFirst("javac").orElseThrow();
        var output = new StringWriter();
        var status = javac.run(new PrintWriter(output), new PrintWriter(output), arguments.toArray(String[]::new));
        assertThat(status).as(output.toString()).isZero();
        return classes;
    }

    private static String modulePath() {
        return MODULES.stream()
                .map(TomlResourceBundleLoadingTest::location)
                .map(Path::toString)
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static Path location(String module) {
        var reference = ModuleLayer.boot()
                .configuration()
                .findModule(module)
                .orElseThrow()
                .reference();
        return Path.of(reference.location().orElseThrow());
    }

    private static URL url(Path path) throws MalformedURLException {
        return path.toUri().toURL();
    }
}
