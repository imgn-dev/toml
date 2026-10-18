package be.imgn.toml;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.CLASS;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Marks a record that documents bind to, with {@link Toml#parse(String, Class)}.
 *
 * <pre>{@code
 * @TomlConfig
 * record AppConfig(String title, Server server, List<Endpoint> endpoints) {}
 * record Server(String host, int port, @Nullable Duration timeout) {}
 * record Endpoint(String path, Set<String> methods) {}
 *
 * AppConfig config = Toml.parse(file, AppConfig.class);
 * }</pre>
 *
 * <p>The annotation processor of the {@code be.imgn.toml:toml-processor} artifact generates the code that binds a table
 * to the record, and registers it as a {@link TomlBinder} service. It rejects at compile time a type it cannot bind.
 * The records the record refers to are bound the same way, without the annotation.
 *
 * <p>Each component of a record is the value of a key of the table, in kebab case: {@code timeoutSeconds} is the value
 * of {@code timeout-seconds}. {@link TomlKey} gives another key. A key the table lacks is an error, unless the
 * component is annotated {@code @Nullable}, which makes it {@code null}. A key of the table that no component takes is
 * an error too.
 *
 * <p>The values are bound as follows; anything else is an error, reported as a {@link TomlMappingException}:
 *
 * <ul>
 *   <li>{@code String}, from a string;
 *   <li>{@code long}, {@code int}, {@code short} and {@code byte}, from an integer in their range;
 *   <li>{@code double} and {@code float}, from a float, or from an integer they represent exactly;
 *   <li>{@code boolean}, from a boolean;
 *   <li>{@code OffsetDateTime}, {@code LocalDateTime}, {@code LocalDate} and {@code LocalTime}, from the date-time of
 *       the same kind;
 *   <li>an enum, from a string naming a constant in kebab case: {@code READ_ONLY} is {@code "read-only"}, unless the
 *       constant has a {@link TomlKey};
 *   <li>{@code Duration}, from a string in ISO-8601, as {@code "PT1M30S"}, or of amounts with units, as {@code "1m30s"}
 *       (see {@link TomlBindings#asDuration(TomlValue, String)});
 *   <li>{@code Path}, from a string, on the default file system;
 *   <li>{@code URI}, from a string;
 *   <li>{@code List<T>} and {@code Set<T>}, from an array of values that bind to {@code T}; a set rejects duplicates;
 *   <li>{@code Map<String, T>}, from a table of values that bind to {@code T}, whatever their keys;
 *   <li>a record, from a table;
 *   <li>{@link TomlValue} and its subtypes, as they are.
 * </ul>
 *
 * <p>The boxed types of the primitive types bind as the primitive types. Lists, sets and maps keep the order of the
 * document, and cannot be modified.
 *
 * @since 0.1.0
 */
@Documented
@Retention(CLASS)
@Target(TYPE)
public @interface TomlConfig {}
