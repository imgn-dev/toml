# toml

A [TOML 1.1](https://toml.io/en/v1.1.0) parser and writer for Java 21+, with no dependency besides
[gaia-core](https://github.com/imgn-dev/gaia).

- Accepts every valid document and rejects every invalid one in the
  [toml-test](https://github.com/toml-lang/toml-test) suite (TOML 1.1.0 manifest).
- Parses into immutable values: a sealed `TomlValue` hierarchy of records.
- Reads from gaia's `CharSource` and `ByteSource`, and writes to its `CharSink` and `ByteSink`.
- Reports errors with their line and column, and what was expected and found.
- Tells the line and column of each key and value, for tools that report problems with them.
- Binds documents to records, through code an annotation processor generates: no reflection.
- Loads `ResourceBundle`s from TOML resources, whose values keep their type.

## Coordinates

```xml
<dependency>
  <groupId>be.imgn.toml</groupId>
  <artifactId>toml</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

The module is `be.imgn.toml`. To bind documents to records, see [Binding to records](#binding-to-records).

## Usage

```java
TomlTable config = Toml.parse(file).root();

String host = config.getString("server.host");
int port = config.getInt("server.port");
Optional<TomlValue> name = config.get("site.\"example.com\".name");

TomlTable updated = config.toBuilder()
        .put("title", "example")
        .build();
Toml.write(updated, CharSink.of(file, StandardCharsets.UTF_8));
```

`Toml.parse` also takes a `String`, a `CharSource` or a `ByteSource` (UTF-8), and `Toml.format` writes a table to a `String`.
It returns a `TomlDocument`: the root table, and the line and column of each key and value. A position is looked up by
the table or array that holds the key or the element, which must be the very instance the parse returned:

```java
TomlDocument document = Toml.parse(file);
TomlTable server = document.root().getTable("server");
TomlPosition port = document.positions().value(server, "port");
```

`Toml.writeAtomically(table, file)` replaces a file so that readers, or the next run after a crash, never see a
half-written document.

| TOML             | Value                | Java                |
|------------------|----------------------|---------------------|
| String           | `TomlString`         | `String`            |
| Integer          | `TomlInteger`        | `long`              |
| Float            | `TomlFloat`          | `double`            |
| Boolean          | `TomlBoolean`        | `boolean`           |
| Offset Date-Time | `TomlOffsetDateTime` | `OffsetDateTime`    |
| Local Date-Time  | `TomlLocalDateTime`  | `LocalDateTime`     |
| Local Date       | `TomlLocalDate`      | `LocalDate`         |
| Local Time       | `TomlLocalTime`      | `LocalTime`         |
| Array            | `TomlArray`          | `List<TomlValue>`   |
| Table            | `TomlTable`          | `Map<String, TomlValue>`, in document order |

The writer keeps the data, not the layout: comments and formatting are not kept, and a table's entries are written
before its subtables. It writes only TOML 1.0 notation, so TOML 1.0 parsers can read its output too.

## Binding to records

Annotate the root record with `@TomlConfig`, and add the processor to the compiler:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <annotationProcessorPaths>
      <path>
        <groupId>be.imgn.toml</groupId>
        <artifactId>toml-processor</artifactId>
        <version>0.1.0-SNAPSHOT</version>
      </path>
    </annotationProcessorPaths>
  </configuration>
</plugin>
```

This works for modular projects too: the Maven Compiler Plugin 3.x passes these paths to `-processorpath`. The
processor is also the named module `be.imgn.toml.processor`, which provides `javax.annotation.processing.Processor`. To
run it from javac's `--processor-module-path`, that path needs `toml-processor`, `gaia-processing`, `gaia-core`,
`jspecify` and `error_prone_annotations` (which `gaia-core` requires).

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

- Keys are the component names in kebab case (`logLevel` is `log-level`), and enum constants too (`READ_ONLY` is
  `"read-only"`); `@TomlKey` gives another one.
- A missing key is an error unless the component is `@Nullable`; a key no component takes is an error too.
- Binds strings, integers (range-checked), floats (and integers a float represents exactly), booleans, the four
  date-times, enums, `Duration` (`"PT1M30S"` or `"1m30s"`), `Path`, `URI`, `List`, `Set`, `Map<String, T>`, records,
  and `TomlValue`s as they are. See `@TomlConfig` for the details.
- A value that does not bind throws a `TomlMappingException` with its path, such as
  `endpoints[0].methods[1]: expected a string, found an integer`. An unsupported type is a compile error.

The processor registers each generated binder in `META-INF/services`, which is enough on the class path. On the module
path, the module must also provide `be.imgn.toml.TomlBinder` with each binder, which a processor cannot declare. gaia's
Maven plugin adds those `provides` clauses to the compiled `module-info.class`:

```xml
<plugin>
  <groupId>be.imgn.gaia</groupId>
  <artifactId>gaia-maven-plugin</artifactId>
  <version>0.1.2-SNAPSHOT</version>
  <executions>
    <execution>
      <goals>
        <goal>register-services</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

## Resource bundles

A `TomlResourceBundle`, in the package `be.imgn.toml.i18n`, flattens tables into dotted keys, and its values keep their
type:

```toml
# com/example/Messages_fr.toml
title = "Inventaire"
days = ["lundi", "mardi"]

[errors]
not-found = "Aucun article {0}"
retries = 3
```

```java
var messages = (TomlResourceBundle)
        ResourceBundle.getBundle("com.example.Messages", locale, TomlResourceBundle.control());

String notFound = messages.getString("errors.not-found");
String[] days = messages.getStringArray("days");
int retries = messages.getInt("errors.retries");
```

- `getObject` returns a `String`, `Long`, `Double`, `Boolean`, one of the four `java.time` types, or a `String[]` for an
  array of strings. Other arrays are an error, and so are two values whose keys flatten to the same one.
- The typed getters (`getLong`, `getInt`, `getDouble`, `getBoolean`, and the date-time getters) look through the parent
  bundles, as `getObject` does.
- A named module cannot pass a control to `getBundle`: it provides its bundles with a subclass of
  `TomlResourceBundleProvider` instead, as its Javadoc shows.

## Building

```sh
./mvnw verify
```

## License

[Apache License 2.0](LICENSE).

`toml/src/test/resources/toml-test` is a copy of the [toml-test](https://github.com/toml-lang/toml-test) suite, under
its own MIT license.
