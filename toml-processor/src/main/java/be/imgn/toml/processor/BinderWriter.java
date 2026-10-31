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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Writes the source of a binder, after its package declaration and imports. */
final class BinderWriter {

    // The TOML value that reverses each conversion of TomlBindings; those written as strings use toString().
    private static final Map<String, String> WRITTEN_TYPES = Map.ofEntries(
            Map.entry("asString", "TomlString"),
            Map.entry("asLong", "TomlInteger"),
            Map.entry("asInt", "TomlInteger"),
            Map.entry("asShort", "TomlInteger"),
            Map.entry("asByte", "TomlInteger"),
            Map.entry("asDouble", "TomlFloat"),
            Map.entry("asFloat", "TomlFloat"),
            Map.entry("asBoolean", "TomlBoolean"),
            Map.entry("asOffsetDateTime", "TomlOffsetDateTime"),
            Map.entry("asLocalDateTime", "TomlLocalDateTime"),
            Map.entry("asLocalDate", "TomlLocalDate"),
            Map.entry("asLocalTime", "TomlLocalTime"),
            Map.entry("asDuration", "TomlString"),
            Map.entry("asPath", "TomlString"),
            Map.entry("asUri", "TomlString"));
    private static final Set<String> WRITTEN_WITH_TO_STRING = Set.of("asDuration", "asPath", "asUri");

    private final SourceScope scope;
    private final String bindings;
    private final String value;
    private final String table;

    private BinderWriter(SourceScope scope) {
        this.scope = scope;
        this.bindings = scope.name("be.imgn.toml.TomlBindings");
        this.value = scope.name("be.imgn.toml.TomlValue");
        this.table = scope.name("be.imgn.toml.TomlTable");
    }

    static String write(SourceScope scope, Binder binder) {
        return new BinderWriter(scope).binder(binder);
    }

    private String binder(Binder binder) {
        var root = scope.name(binder.root());
        var methods = Stream.concat(
                        binder.records().stream().map(method -> recordMethod(method) + recordWriter(method)),
                        binder.enums().stream().map(method -> enumMethod(method) + enumWriter(method)))
                .collect(Collectors.joining());
        var first = binder.records().getFirst();
        return """
                /** Binds tables to {@code %1$s}, and writes it to tables. */
                %2$s
                public final class %3$s implements %4$s<%1$s> {

                    /** Creates the binder; the service loader does. */
                    public %3$s() {}

                    @Override
                    public Class<%1$s> type() {
                        return %1$s.class;
                    }

                    @Override
                    public %1$s bind(%5$s table, %6$s unknownKeys) {
                        return %7$s(table, new %8$s.Context("", unknownKeys));
                    }

                    @Override
                    public %5$s toTable(%1$s value) {
                        return %9$s(value);
                    }
                %10$s}
                """.formatted(
                        root,
                        scope.generated(),
                        scope.simpleName(),
                        scope.name("be.imgn.toml.TomlBinder"),
                        table,
                        scope.name("be.imgn.toml.UnknownKeys"),
                        first.name(),
                        bindings,
                        first.writer(),
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

                    private static %1$s %2$s(%3$s value, %4$s.Context context) {
                        var table = %4$s.table(value, context%5$s);
                %6$s        try {
                            return new %1$s(%7$s);
                        } catch (RuntimeException e) {
                            throw %4$s.rejected(context, e);
                        }
                    }
                """.formatted(type, method.name(), value, bindings, keys, variables, arguments);
    }

    private String recordWriter(RecordMethod method) {
        var puts = method.components().stream()
                .map(component -> "        %s.%s(builder, %s, value.%s(), %s);\n"
                        .formatted(
                                bindings,
                                component.optional() ? "putIfNotNull" : "put",
                                literal(component.key()),
                                component.accessor(),
                                writer(component.conversion(), 1)))
                .collect(Collectors.joining());
        return """

                    private static %1$s %2$s(%3$s value) {
                        var builder = %1$s.builder();
                %4$s        return builder.build();
                    }
                """.formatted(table, method.writer(), scope.name(method.type()), puts);
    }

    private String componentVariable(Component component) {
        return "        var %s = %s.%s(table, context, %s, %s);\n"
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

                    private static %1$s %2$s(%3$s value, %4$s.Context context) {
                        return switch (%4$s.asString(value, context)) {
                %5$s            default -> throw %4$s.notOneOf(value, context%6$s);
                        };
                    }
                """.formatted(type, method.name(), value, bindings, cases, keys);
    }

    private String enumWriter(EnumMethod method) {
        var cases = method.constants().stream()
                .map(constant -> "            case %s -> %s;\n".formatted(constant.name(), literal(constant.key())))
                .collect(Collectors.joining());
        return """

                    private static %1$s %2$s(%3$s value) {
                        return new %1$s(switch (value) {
                %4$s        });
                    }
                """.formatted(scope.name("be.imgn.toml.TomlString"), method.writer(), scope.name(method.type()), cases);
    }

    private String conversion(Conversion conversion) {
        return switch (conversion) {
            case BindingsMethod(var name) -> bindings + "::" + name;
            case ValueType(var qualifiedName) -> bindings + ".as(" + scope.name(qualifiedName) + ".class)";
            case Container(var factory, var element) -> bindings + "." + factory + "(" + conversion(element) + ")";
            case BinderMethod(var name, var writer) -> scope.simpleName() + "::" + name;
        };
    }

    /** Returns a lambda that writes a value, its parameter named after its depth, so that nested lambdas differ. */
    private String writer(Conversion conversion, int depth) {
        var parameter = "e" + depth;
        var written = switch (conversion) {
            case BindingsMethod(var name) ->
                "new %s(%s%s)"
                        .formatted(
                                scope.name("be.imgn.toml." + WRITTEN_TYPES.get(name)),
                                parameter,
                                WRITTEN_WITH_TO_STRING.contains(name) ? ".toString()" : "");
            case ValueType valueType -> parameter;
            case Container(var factory, var element) ->
                "%s.%s(%s, %s)"
                        .formatted(
                                bindings,
                                factory.equals("map") ? "toTable" : "toArray",
                                parameter,
                                writer(element, depth + 1));
            case BinderMethod(var name, var method) -> method + "(" + parameter + ")";
        };
        return parameter + " -> " + written;
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
