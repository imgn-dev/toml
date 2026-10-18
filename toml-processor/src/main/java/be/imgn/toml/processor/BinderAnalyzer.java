package be.imgn.toml.processor;

import static java.util.Map.entry;

import be.imgn.gaia.core.text.Case;
import be.imgn.gaia.processing.MoreElements;
import be.imgn.gaia.processing.ProcessingContext;
import be.imgn.gaia.processing.ProcessingException;
import be.imgn.gaia.processing.source.NameAllocator;
import be.imgn.toml.processor.Binder.BinderMethod;
import be.imgn.toml.processor.Binder.BindingsMethod;
import be.imgn.toml.processor.Binder.Component;
import be.imgn.toml.processor.Binder.Constant;
import be.imgn.toml.processor.Binder.Container;
import be.imgn.toml.processor.Binder.Conversion;
import be.imgn.toml.processor.Binder.EnumMethod;
import be.imgn.toml.processor.Binder.RecordMethod;
import be.imgn.toml.processor.Binder.ValueType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Works out the binder of a record: how each of its components converts, and the records and enums they refer to. Each
 * invalid component or constant is reported as an error.
 */
final class BinderAnalyzer {

    static final String TOML_KEY = "be.imgn.toml.TomlKey";

    private static final Map<TypeKind, String> PRIMITIVES = Map.of(
            TypeKind.LONG, "asLong",
            TypeKind.INT, "asInt",
            TypeKind.SHORT, "asShort",
            TypeKind.BYTE, "asByte",
            TypeKind.DOUBLE, "asDouble",
            TypeKind.FLOAT, "asFloat",
            TypeKind.BOOLEAN, "asBoolean");
    private static final Map<String, String> CLASSES = Map.ofEntries(
            entry("java.lang.String", "asString"),
            entry("java.lang.Long", "asLong"),
            entry("java.lang.Integer", "asInt"),
            entry("java.lang.Short", "asShort"),
            entry("java.lang.Byte", "asByte"),
            entry("java.lang.Double", "asDouble"),
            entry("java.lang.Float", "asFloat"),
            entry("java.lang.Boolean", "asBoolean"),
            entry("java.time.OffsetDateTime", "asOffsetDateTime"),
            entry("java.time.LocalDateTime", "asLocalDateTime"),
            entry("java.time.LocalDate", "asLocalDate"),
            entry("java.time.LocalTime", "asLocalTime"),
            entry("java.time.Duration", "asDuration"),
            entry("java.nio.file.Path", "asPath"),
            entry("java.net.URI", "asUri"));
    private static final Set<String> VALUE_TYPES = Stream.of(
                    "TomlValue",
                    "TomlTable",
                    "TomlArray",
                    "TomlString",
                    "TomlInteger",
                    "TomlFloat",
                    "TomlBoolean",
                    "TomlOffsetDateTime",
                    "TomlLocalDateTime",
                    "TomlLocalDate",
                    "TomlLocalTime")
            .map(name -> "be.imgn.toml." + name)
            .collect(Collectors.toUnmodifiableSet());
    private static final Map<String, String> COLLECTIONS = Map.of("java.util.List", "list", "java.util.Set", "set");

    private final ProcessingContext context;
    private final String packageName;
    // The binder's own methods are reserved, so that no conversion method takes their names.
    private final NameAllocator methodNames = new NameAllocator();
    private final Map<String, String> methodsByType = new HashMap<>();
    private final Queue<Pending> pending = new ArrayDeque<>();

    BinderAnalyzer(ProcessingContext context, TypeElement root) {
        this.context = context;
        this.packageName = MoreElements.packageOf(root).getQualifiedName().toString();
        methodNames.newName("type");
        methodNames.newName("bind");
        method(root);
    }

    /** Works out the binder, or returns empty if an error was reported. */
    Optional<Binder> analyze() {
        var errors = context.diagnostics().errorCount();
        var records = new ArrayList<RecordMethod>();
        var enums = new ArrayList<EnumMethod>();
        while (!pending.isEmpty()) {
            var next = pending.remove();
            var type = next.type();
            if (type.getKind() == ElementKind.ENUM) {
                enums.add(new EnumMethod(type, next.name(), constants(type)));
            } else {
                records.add(new RecordMethod(type, next.name(), components(type)));
            }
        }
        return context.diagnostics().errorCount() == errors
                ? Optional.of(new Binder(records, enums))
                : Optional.empty();
    }

    private List<Component> components(TypeElement type) {
        var variables = new NameAllocator();
        // The names of the generated method's parameters and variables.
        Stream.of("value", "path", "table", "e").forEach(variables::newName);
        var keys = new HashSet<String>();
        var components = new ArrayList<Component>();
        context.forEach(type.getRecordComponents(), component -> {
            var name = component.getSimpleName().toString();
            var key = key(component, name, Case.LOWER_CAMEL);
            if (!keys.add(key)) {
                throw new ProcessingException("Another component already has the key \"" + key + "\"", component);
            }
            var optional = isNullable(component);
            if (optional && component.asType().getKind().isPrimitive()) {
                throw new ProcessingException(
                        "A component of a primitive type cannot be @Nullable: use its boxed type", component);
            }
            components.add(
                    new Component(variables.newName(name), key, optional, conversion(component.asType(), component)));
        });
        return components;
    }

    private List<Constant> constants(TypeElement type) {
        var keys = new HashSet<String>();
        var constants = new ArrayList<Constant>();
        var elements = type.getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.ENUM_CONSTANT)
                .toList();
        context.forEach(elements, constant -> {
            var name = constant.getSimpleName().toString();
            var key = key(constant, name, Case.UPPER_SNAKE);
            if (!keys.add(key)) {
                throw new ProcessingException("Another constant already has the key \"" + key + "\"", constant);
            }
            constants.add(new Constant(key, name));
        });
        return constants;
    }

    private String key(Element element, String name, Case nameCase) {
        return context.annotation(element, TOML_KEY)
                .map(annotation -> annotation.string("value"))
                .orElseGet(() -> kebab(element, name, nameCase));
    }

    private static String kebab(Element element, String name, Case nameCase) {
        try {
            return nameCase.to(Case.KEBAB, name);
        } catch (IllegalArgumentException e) {
            throw new ProcessingException(
                    "Cannot derive a key from the name " + name + ": give it one with @TomlKey", element);
        }
    }

    private Conversion conversion(TypeMirror type, RecordComponentElement component) {
        var primitive = PRIMITIVES.get(type.getKind());
        if (primitive != null) {
            return new BindingsMethod(primitive);
        }
        if (type.getKind() != TypeKind.DECLARED) {
            throw unsupported(type, component);
        }
        var declared = (DeclaredType) type;
        var element = MoreElements.asType(declared.asElement());
        var name = element.getQualifiedName().toString();
        return scalar(name)
                .or(() -> container(name, declared.getTypeArguments(), component))
                .or(() -> bound(element, component))
                .orElseThrow(() -> unsupported(type, component));
    }

    private static Optional<Conversion> scalar(String name) {
        if (VALUE_TYPES.contains(name)) {
            return Optional.of(new ValueType(name));
        }
        return Optional.ofNullable(CLASSES.get(name)).map(BindingsMethod::new);
    }

    private Optional<Conversion> container(
            String name, List<? extends TypeMirror> arguments, RecordComponentElement component) {
        var collection = COLLECTIONS.get(name);
        if (collection != null && arguments.size() == 1) {
            return Optional.of(new Container(collection, conversion(arguments.getFirst(), component)));
        }
        if ("java.util.Map".equals(name) && arguments.size() == 2 && isString(arguments.getFirst())) {
            return Optional.of(new Container("map", conversion(arguments.get(1), component)));
        }
        return Optional.empty();
    }

    private Optional<Conversion> bound(TypeElement type, RecordComponentElement component) {
        var kind = type.getKind();
        if ((kind == ElementKind.RECORD && type.getTypeParameters().isEmpty()) || kind == ElementKind.ENUM) {
            checkAccessible(type, component);
            return Optional.of(new BinderMethod(method(type)));
        }
        return Optional.empty();
    }

    private static boolean isString(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED
                && MoreElements.asType(((DeclaredType) type).asElement())
                        .getQualifiedName()
                        .contentEquals("java.lang.String");
    }

    private void checkAccessible(TypeElement type, Element component) {
        if (type.getNestingKind() == NestingKind.LOCAL || !MoreElements.isAccessible(type, packageName)) {
            throw new ProcessingException(
                    "Cannot bind " + type.getQualifiedName() + ": it is not accessible from the package "
                            + (packageName.isEmpty() ? "(unnamed)" : packageName),
                    component);
        }
    }

    /** Returns the name of the method that binds a record or an enum, which is analyzed once. */
    private String method(TypeElement type) {
        return methodsByType.computeIfAbsent(type.getQualifiedName().toString(), qualifiedName -> {
            var simpleName = type.getSimpleName().toString();
            var name =
                    methodNames.newName(simpleName.substring(0, 1).toLowerCase(Locale.ROOT) + simpleName.substring(1));
            pending.add(new Pending(type, name));
            return name;
        });
    }

    private static boolean isNullable(RecordComponentElement component) {
        return Stream.concat(
                        component.getAnnotationMirrors().stream(), component.asType().getAnnotationMirrors().stream())
                .map(AnnotationMirror::getAnnotationType)
                .anyMatch(annotation -> annotation.asElement().getSimpleName().contentEquals("Nullable"));
    }

    private static ProcessingException unsupported(TypeMirror type, Element component) {
        return new ProcessingException(
                "Cannot bind the type " + type + ": see @TomlConfig for the types it binds", component);
    }

    /** A record or an enum to analyze, and the name of the method that binds it. */
    private record Pending(TypeElement type, String name) {}
}
