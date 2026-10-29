package be.imgn.toml.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessorModuleTest {

    /** The modules javac runs the processor from, besides this one: those it requires. */
    private static final List<String> PROCESSOR_MODULES = List.of(
            "be.imgn.gaia.processing", "be.imgn.gaia.core", "org.jspecify", "com.google.errorprone.annotations");

    /** The modules the application compiles against. */
    private static final List<String> APPLICATION_MODULES =
            List.of("be.imgn.toml", "be.imgn.gaia.core", "org.jspecify", "com.google.errorprone.annotations");

    @TempDir
    Path directory;

    @Test
    void runsFromProcessorModulePath() throws IOException, InterruptedException {
        var sources = directory.resolve("sources");
        var classes = directory.resolve("classes");
        var moduleInfo = write(sources.resolve("module-info.java"), """
                module app {
                    requires be.imgn.toml;
                }
                """);
        var config = write(sources.resolve("app/AppConfig.java"), """
                package app;

                import be.imgn.toml.TomlConfig;

                @TomlConfig
                public record AppConfig(String title) {}
                """);

        // In another process: in this one, javac would also find the processor that this module, as the tests run it,
        // provides.
        var javac = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "javac").toString(),
                        "-d",
                        classes.toString(),
                        "--module-path",
                        modulePath(APPLICATION_MODULES),
                        "--processor-module-path",
                        // The main output, whose descriptor is the one to test, not this module's.
                        System.getProperty("main.classes") + File.pathSeparator + modulePath(PROCESSOR_MODULES),
                        moduleInfo.toString(),
                        config.toString())
                .redirectErrorStream(true)
                .start();
        var output = new String(javac.getInputStream().readAllBytes(), Charset.defaultCharset());

        assertThat(javac.waitFor()).as(output).isZero();
        assertThat(classes.resolve("app/$AppConfig$$TomlBinder.class")).exists();
    }

    private static Path write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }

    private static String modulePath(List<String> modules) {
        return modules.stream()
                .map(ProcessorModuleTest::location)
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
}
