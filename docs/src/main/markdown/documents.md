# Documents

## Parsing

[`Toml.parse`](api:be.imgn.toml.Toml) reads a document from a `String`, a `Path`, a `CharSource` or a `ByteSource`
(UTF-8), and returns a [`TomlDocument`](api:be.imgn.toml.TomlDocument): the root table, and the
[positions](positions.md) of its keys and values.

```java
TomlTable config = Toml.parse(file).root();

String host = config.getString("server.host");
int port = config.getInt("server.port");
Optional<TomlValue> name = config.get("site.\"example.com\".name");
```

A document that is not valid TOML throws a [`TomlParseException`](api:be.imgn.toml.TomlParseException), whose
message starts with the line and column of the error, and states what was expected and what was found.

## Values

A document is made of immutable values: [`TomlValue`](api:be.imgn.toml.TomlValue) is a sealed interface, and each kind
of TOML value is a record that implements it, so that `switch` can match them.

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

## Tables

A [`TomlTable`](api:be.imgn.toml.TomlTable) keeps its keys in the order they appear in the document. Besides its
`entries()`, it looks values up by a TOML key, which can be dotted and quoted as in a document:
`get("server.port")` is the value of the key `port` in the table `server`, and `get("site.\"example.com\"")` the value
of the key `example.com` in the table `site`.

`get` returns an `Optional`. The typed getters, such as `getString`, `getInt` or `getTable`, throw
`NoSuchElementException` when the key is missing and `ClassCastException` when the value has another type; `getInt`
throws `ArithmeticException` when the integer doesn't fit in an `int`.

Two tables are equal when they have the same entries, whatever their order.

A [`TomlTable.Builder`](api:be.imgn.toml.TomlTable.Builder) builds a table, from `TomlTable.builder()` or from an
existing table with `toBuilder()`. Its keys are taken as they are: a dot is part of the key and does not nest tables.

```java
TomlTable updated = config.toBuilder()
        .put("title", "example")
        .build();
```

## Writing

`Toml.format` writes a table to a `String`, `Toml.write` to a `CharSink` or a `ByteSink` (UTF-8), and
`Toml.writeAtomically` to a file, so that readers, or the next run after a crash, never see a half-written document.

```java
Toml.write(updated, CharSink.of(file, StandardCharsets.UTF_8));
Toml.writeAtomically(updated, file);
```

The writer keeps the data, not the layout: comments and formatting are not kept, and a table's entries are written
before its subtables. It writes only TOML 1.0 notation, so TOML 1.0 parsers can read its output too.
