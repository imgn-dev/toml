package be.imgn.toml.processor;

import be.imgn.gaia.processing.ProcessingContext;
import be.imgn.gaia.processing.Step;
import be.imgn.gaia.processing.StepProcessor;
import java.util.List;

/**
 * Generates a {@code be.imgn.toml.TomlBinder} for each record annotated {@code be.imgn.toml.TomlConfig}, and registers
 * it as a service.
 */
public final class TomlProcessor extends StepProcessor {

    /** Creates the processor; javac does, with the service loader. */
    public TomlProcessor() {}

    @Override
    protected List<Step> steps(ProcessingContext context) {
        return List.of(new BinderStep(context));
    }
}
