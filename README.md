# toml

A [TOML 1.1](https://toml.io/en/v1.1.0) parser and writer for Java 21+, with no dependency besides
[gaia-core](https://github.com/imgn-dev/gaia).

- Accepts every valid document and rejects every invalid one in the
  [toml-test](https://github.com/toml-lang/toml-test) suite (TOML 1.1.0 manifest).
- Parses into immutable values: a sealed `TomlValue` hierarchy of records.
- Reads from gaia's `CharSource` and `ByteSource`, and writes to its `CharSink` and `ByteSink`.
- Reports errors with their line and column, and what was expected and found.

## Coordinates

```xml
<dependency>
  <groupId>be.imgn.toml</groupId>
  <artifactId>toml</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

The module is `be.imgn.toml`.

## Usage

```java
TomlTable config = Toml.parse(CharSource.of(file, StandardCharsets.UTF_8));

String host = config.getString("server.host");
long port = config.getLong("server.port");
Optional<TomlValue> name = config.get("site.\"example.com\".name");

TomlTable updated = TomlTable.builder()
        .put("title", new TomlString("example"))
        .put("server", config.getTable("server"))
        .build();
Toml.write(updated, CharSink.of(file, StandardCharsets.UTF_8));
```

`Toml.parse` also takes a `String` or a `ByteSource` (UTF-8), and `Toml.format` writes a table to a `String`.
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

## Building

```sh
./mvnw verify
```

## License

[Apache License 2.0](LICENSE).

`src/test/resources/toml-test` is a copy of the [toml-test](https://github.com/toml-lang/toml-test) suite, under
its own MIT license.
