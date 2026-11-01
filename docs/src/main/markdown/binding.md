# Binding to records

A document binds to a record annotated [`@TomlConfig`](api:be.imgn.toml.TomlConfig), through code that an annotation
processor generates at compile time: there is no reflection, and a type that cannot be bound is a compile error.

```java
@TomlConfig
record AppConfig(String title, Server server, List<Endpoint> endpoints) {}
record Server(String host, int port, @Nullable Duration timeout, LogLevel logLevel) {}
record Endpoint(String path, Set<String> methods) {}
enum LogLevel { DEBUG, INFO, READ_ONLY }

AppConfig config = Toml.parse(file, AppConfig.class);
```

```toml
title = "Inventory"

[server]
host = "0.0.0.0"
port = 8080
log-level = "info"   # timeout is @Nullable, so it may be left out

[[endpoints]]
path = "/items"
methods = ["GET", "POST"]
```

The records the annotated record refers to are bound the same way, without the annotation.

## Setting up the processor

Add `toml-processor` to the annotation processor path:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <annotationProcessorPaths>
      <path>
        <groupId>be.imgn.toml</groupId>
        <artifactId>toml-processor</artifactId>
        <version>${toml.version}</version>
      </path>
    </annotationProcessorPaths>
  </configuration>
</plugin>
```

This works for modular projects too: the Maven Compiler Plugin 3.x passes these paths to `-processorpath`. The
processor is also the named module `be.imgn.toml.processor`, which provides `javax.annotation.processing.Processor`. To
run it from javac's `--processor-module-path`, that path needs `toml-processor`, `gaia-processing`, `gaia-core`,
`jspecify` and `error_prone_annotations` (which `gaia-core` requires).

### On the module path

The processor registers each binder it generates in `META-INF/services`, which is enough on the class path. On the
module path, the module must also provide [`TomlBinder`](api:be.imgn.toml.TomlBinder) with each binder, which a
processor cannot declare. gaia's Maven plugin adds those `provides` directives to the compiled `module-info.class`:

```xml
<plugin>
  <groupId>be.imgn.gaia</groupId>
  <artifactId>gaia-maven-plugin</artifactId>
  <version>${gaia.version}</version>
  <executions>
    <execution>
      <goals>
        <goal>register-services</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

## Keys

Each component of a record is the value of a key of the table, in kebab case: `logLevel` is the value of `log-level`.
[`@TomlKey`](api:be.imgn.toml.TomlKey) gives another key, taken as is: a dot is part of the key and does not nest
tables.

```java
record Database(@TomlKey("jdbc_url") String url) {}
```

A key the table lacks is an error, unless the component is annotated `@Nullable`, which makes it `null`. A key of the
table that no component takes is an error too, unless the table is bound with
[`UnknownKeys.IGNORE`](#tables-in-memory).

## Types

| Java                                                   | TOML                                               |
|--------------------------------------------------------|----------------------------------------------------|
| `String`                                               | a string                                           |
| `long`, `int`, `short`, `byte`                         | an integer in their range                          |
| `double`, `float`                                      | a float, or an integer they represent exactly      |
| `boolean`                                              | a boolean                                          |
| `OffsetDateTime`, `LocalDateTime`, `LocalDate`, `LocalTime` | the date-time of the same kind                |
| an enum                                                | a string naming a constant, [in kebab case](#enums) |
| `Duration`                                             | a string, [in ISO-8601 or with units](#durations)  |
| `Path`                                                 | a string, on the default file system               |
| `URI`                                                  | a string                                           |
| `List<T>`, `Set<T>`                                    | an array of values that bind to `T`; a set rejects duplicates |
| `Map<String, T>`                                       | a table of values that bind to `T`, whatever their keys |
| a record                                               | a table                                            |
| `TomlValue` and its subtypes                           | the value as it is                                 |

The boxed types of the primitive types bind as the primitive types. Lists, sets and maps keep the order of the
document, and cannot be modified.

### Enums

A constant is named in kebab case: `READ_ONLY` is `"read-only"`. `@TomlKey` gives a constant another string:

```java
enum Level {
    @TomlKey("warn") WARNING,
    ERROR
}
```

### Durations

A `Duration` is either in ISO-8601, as `Duration.parse` reads it, such as `"PT1M30S"` or `"-P2DT3H"`, or one or more
amounts, each followed by its unit, such as `"1h30m"`, `"2.5s"` or `"-500ms"`. The units are `d` (24 hours), `h`, `m`,
`s`, `ms`, `us` and `ns`, in decreasing order and at most once each, after an optional `-` sign. The total must be a
whole number of nanoseconds.

## Errors

A value that does not bind throws a [`TomlMappingException`](api:be.imgn.toml.TomlMappingException) with its path,
such as `endpoints[0].methods[1]: expected a string, found an integer`.

## Tables in memory

The generated code also binds a table already in memory, and works the other way, from a record to a table:

```java
TomlTable table = Toml.toTable(config);               // binds back to an equal record
Toml.writeAtomically(table, file);

AppConfig same = Toml.bind(table, AppConfig.class);
AppConfig lenient = Toml.bind(table, AppConfig.class, UnknownKeys.IGNORE);
```

`toTable` writes each value in the form it binds from: a `Duration` in ISO-8601, an enum as its key, and so on; a null
`@Nullable` component is left out. [`UnknownKeys.IGNORE`](api:be.imgn.toml.UnknownKeys) ignores the keys no component
takes, in every record, so that a file written by another version of the application still binds.
