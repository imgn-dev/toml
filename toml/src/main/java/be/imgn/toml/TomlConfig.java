package be.imgn.toml;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.CLASS;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/// Marks a record that documents bind to, with [Toml#parse(String, Class)] or [Toml#bind(TomlTable, Class)].
///
/// ```java
/// @TomlConfig
/// record AppConfig(String title, Server server, List<Endpoint> endpoints) {}
/// record Server(String host, int port, @Nullable Duration timeout) {}
/// record Endpoint(String path, Set<String> methods) {}
///
/// AppConfig config = Toml.parse(file, AppConfig.class);
/// ```
///
/// The annotation processor of the `be.imgn.toml:toml-processor` artifact generates the code that binds a table
/// to the record, and registers it as a [TomlBinder] service. It rejects at compile time a type it cannot bind.
/// The records the record refers to are bound the same way, without the annotation.
///
/// Each component of a record is the value of a key of the table, in kebab case: `timeoutSeconds` is the value
/// of `timeout-seconds`. [TomlKey] gives another key. A key the table lacks is an error, unless the
/// component is annotated `@Nullable`, which makes it `null`. A key of the table that no component takes is
/// an error too, unless the table is bound with [UnknownKeys#IGNORE].
///
/// The values are bound as follows; anything else is an error, reported as a [TomlMappingException]:
///
/// - `String`, from a string;
/// - `long`, `int`, `short` and `byte`, from an integer in their range;
/// - `double` and `float`, from a float, or from an integer they represent exactly;
/// - `boolean`, from a boolean;
/// - `OffsetDateTime`, `LocalDateTime`, `LocalDate` and `LocalTime`, from the date-time of
///   the same kind;
/// - an enum, from a string naming a constant in kebab case: `READ_ONLY` is `"read-only"`, unless the
///   constant has a [TomlKey];
/// - `Duration`, from a string in ISO-8601, as `"PT1M30S"`, or of amounts with units, as `"1m30s"`
///   (see [TomlBindings#asDuration(TomlValue, TomlBindings.Context)]);
/// - `Path`, from a string, on the default file system;
/// - `URI`, from a string;
/// - `List<T>` and `Set<T>`, from an array of values that bind to `T`; a set rejects duplicates;
/// - `Map<String, T>`, from a table of values that bind to `T`, whatever their keys;
/// - a record, from a table;
/// - [TomlValue] and its subtypes, as they are.
///
/// The boxed types of the primitive types bind as the primitive types. Lists, sets and maps keep the order of
/// the document, and cannot be modified.
///
/// [Toml#toTable(Record)] converts a record back to a table, in the forms above.
///
/// @since 0.1.0
@Documented
@Retention(CLASS)
@Target(TYPE)
public @interface TomlConfig {}
