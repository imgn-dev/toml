package be.imgn.toml.processor;

import be.imgn.gaia.processing.SourceScope;
import be.imgn.toml.processor.Binder.BinderMethod;
import be.imgn.toml.processor.Binder.BindingsMethod;
import be.imgn.toml.processor.Binder.Component;
import be.imgn.toml.processor.Binder.Container;
import be.imgn.toml.processor.Binder.Conversion;
import be.imgn.toml.processor.Binder.EnumMethod;
import be.imgn.toml.processor.Binder.RecordMethod;
import be.imgn.toml.processor.Binder.ValueType;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Writes the source of a binder, after its package declaration and imports. */
final class BinderWriter {

    private final SourceScope scope;
    private final String bindings;
    private final String value;

    private BinderWriter(SourceScope scope) {
        this.scope = scope;
        this.bindings = scope.name("be.imgn.toml.TomlBindings");
        this.value = scope.name("be.imgn.toml.TomlValue");
    }

    static String write(SourceScope scope, Binder binder) {
        return new BinderWriter(scope).binder(binder);
    }

    private String binder(Binder binder) {
        var root = scope.name(binder.root());
        var methods = Stream.concat(
                        binder.records().stream().map(this::recordMethod),
                        binder.enums().stream().map(this::enumMethod))
                .collect(Collectors.joining());
        return """
                /** Binds tables to {@code %1$s}. */
                %2$s
                public final class %3$s implements %4$s<%1$s> {

                    /** Creates the binder; the service loader does. */
                    public %3$s() {}

                    @Override
                    public Class<%1$s> type() {
                        return %1$s.class;
                    }

                    @Override
                    public %1$s bind(%5$s table) {
                        return %6$s(table, "");
                    }
                %7$s}
                """.formatted(
                        root,
                        scope.generated(),
                        scope.simpleName(),
                        scope.name("be.imgn.toml.TomlBinder"),
                        scope.name("be.imgn.toml.TomlTable"),
                        binder.records().getFirst().name(),
                        methods);
    }

    private String recordMethod(RecordMethod method) {
        var type = scope.name(method.type());
        var keys = method.components().stream()
                .map(component -> ", " + literal(component.key()))
                .collect(Collectors.joining());
        var variables =
                method.components().stream().map(this::componentVariable).collect(Collectors.joining());
        var arguments = method.components().stream().map(Component::variable).collect(Collectors.joining(", "));
        return """

                    private static %1$s %2$s(%3$s value, String path) {
                        var table = %4$s.table(value, path%5$s);
                %6$s        try {
                            return new %1$s(%7$s);
                        } catch (RuntimeException e) {
                            throw %4$s.rejected(path, e);
                        }
                    }
                """.formatted(type, method.name(), value, bindings, keys, variables, arguments);
    }

    private String componentVariable(Component component) {
        return "        var %s = %s.%s(table, path, %s, %s);\n"
                .formatted(
                        component.variable(),
                        bindings,
                        component.optional() ? "optional" : "required",
                        literal(component.key()),
                        conversion(component.conversion()));
    }

    private String enumMethod(EnumMethod method) {
        var type = scope.name(method.type());
        var cases = method.constants().stream()
                .map(constant ->
                        "            case %s -> %s.%s;\n".formatted(literal(constant.key()), type, constant.name()))
                .collect(Collectors.joining());
        var keys = method.constants().stream()
                .map(constant -> ", " + literal(constant.key()))
                .collect(Collectors.joining());
        return """

                    private static %1$s %2$s(%3$s value, String path) {
                        return switch (%4$s.asString(value, path)) {
                %5$s            default -> throw %4$s.notOneOf(value, path%6$s);
                        };
                    }
                """.formatted(type, method.name(), value, bindings, cases, keys);
    }

    private String conversion(Conversion conversion) {
        return switch (conversion) {
            case BindingsMethod(var name) -> bindings + "::" + name;
            case ValueType(var qualifiedName) -> bindings + ".as(" + scope.name(qualifiedName) + ".class)";
            case Container(var factory, var element) -> bindings + "." + factory + "(" + conversion(element) + ")";
            case BinderMethod(var name) -> scope.simpleName() + "::" + name;
        };
    }

    /** Returns a string as a Java string literal. */
    private static String literal(String string) {
        var literal = new StringBuilder("\"");
        string.chars().forEach(c -> {
            switch (c) {
                case '"' -> literal.append("\\\"");
                case '\\' -> literal.append("\\\\");
                case '\n' -> literal.append("\\n");
                case '\r' -> literal.append("\\r");
                case '\t' -> literal.append("\\t");
                default -> {
                    if (c < ' ' || c == '\u007F') {
                        literal.append("\\u%04x".formatted(c));
                    } else {
                        literal.append((char) c);
                    }
                }
            }
        });
        return literal.append('"').toString();
    }
}
