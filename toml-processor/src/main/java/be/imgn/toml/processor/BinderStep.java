package be.imgn.toml.processor;

import be.imgn.gaia.processing.AnnotatedElements;
import be.imgn.gaia.processing.MoreElements;
import be.imgn.gaia.processing.ProcessingContext;
import be.imgn.gaia.processing.ProcessingException;
import be.imgn.gaia.processing.Step;
import be.imgn.gaia.processing.source.ClassName;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.NestingKind;

/** Generates the binder of each record annotated {@code TomlConfig}. */
final class BinderStep implements Step {

    private static final String TOML_CONFIG = "be.imgn.toml.TomlConfig";
    private static final ClassName TOML_BINDER = ClassName.of("be.imgn.toml", "TomlBinder");

    private final ProcessingContext context;

    BinderStep(ProcessingContext context) {
        this.context = context;
        // On the module path, the provides directives are added to the compiled module descriptor after compilation.
        context.services().skipModuleCheck(TOML_BINDER);
    }

    @Override
    public Set<String> annotations() {
        return Set.of(TOML_CONFIG);
    }

    @Override
    public Set<Element> process(AnnotatedElements elements) {
        context.forEach(elements.annotatedWith(TOML_CONFIG), this::generate);
        return Set.of();
    }

    private void generate(Element element) {
        if (!MoreElements.isRecord(element)) {
            throw new ProcessingException("@TomlConfig must annotate a record", element, TOML_CONFIG);
        }
        var type = MoreElements.asType(element);
        if (!type.getTypeParameters().isEmpty()) {
            throw new ProcessingException("A @TomlConfig record cannot be generic", element, TOML_CONFIG);
        }
        var packageName = MoreElements.packageOf(type).getQualifiedName().toString();
        if (type.getNestingKind() == NestingKind.LOCAL || !MoreElements.isAccessible(type, packageName)) {
            throw new ProcessingException(
                    "A @TomlConfig record must be accessible from its package", element, TOML_CONFIG);
        }
        new BinderAnalyzer(context, type).analyze().ifPresent(binder -> {
            var name = ClassName.of(type).generatedName("TomlBinder");
            var originating =
                    binder.records().stream().map(Binder.RecordMethod::type).toArray(Element[]::new);
            context.sources().write(name.canonicalName(), scope -> BinderWriter.write(scope, binder), originating);
            context.services().add(TOML_BINDER, name, type);
        });
    }
}
