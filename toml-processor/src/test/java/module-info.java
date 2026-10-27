import be.imgn.toml.processor.TomlProcessor;
import javax.annotation.processing.Processor;

// The processor module as its tests see it: the main descriptor, and the modules the tests use. Without it, the tests
// are patched into the main module and the runtime module is on the class path, where javac does not find its classes.
open module be.imgn.toml.processor {
    requires be.imgn.gaia.core;
    requires be.imgn.gaia.processing;
    requires be.imgn.gaia.processing.testing;
    requires be.imgn.toml;
    requires org.assertj.core;
    requires org.junit.jupiter.api;

    provides Processor with
            TomlProcessor;
}
