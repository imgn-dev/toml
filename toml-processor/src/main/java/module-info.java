import be.imgn.toml.processor.TomlProcessor;
import javax.annotation.processing.Processor;

/**
 * The annotation processor that generates the binders of the records annotated {@code be.imgn.toml.TomlConfig}.
 *
 * <p>It exports no package: javac finds the processor through the service it provides, from the processor module path.
 */
module be.imgn.toml.processor {
    requires be.imgn.gaia.core;
    requires be.imgn.gaia.processing;

    provides Processor with
            TomlProcessor;
}
