# Positions and errors

## Positions of keys and values

A parsed [`TomlDocument`](api:be.imgn.toml.TomlDocument) tells where each key and value is written, as a
[`TomlPosition`](api:be.imgn.toml.TomlPosition): a line and a column, both from 1. Tools that check a document beyond
its syntax use them to report a problem where it is.

```java
TomlDocument document = Toml.parse(file);
TomlTable server = document.root().getTable("server");
TomlPosition port = document.positions().value(server, "port");
```

[`TomlPositions`](api:be.imgn.toml.TomlPositions) has the position of a key (`key`), of its value (`value`), and of an
element of an array (`element`).

- A position is looked up by the table or array that holds the key or the element, which must be the very instance
  the parse returned: positions compare tables and arrays by identity, as equal tables can be written in several
  places. A copy of a table, such as one built from it with `toBuilder()`, has no positions.
- The key is a key of that table, never a dotted path: the position of `port` in `[server]` is
  `key(root.getTable("server"), "port")`.
- A line ends with `\n` or `\r\n`, and a column counts UTF-16 code units, as in a `String`. The byte order mark a
  document may start with is not counted.

## Syntax errors

A document that is not valid TOML throws a [`TomlParseException`](api:be.imgn.toml.TomlParseException). Its message
starts with the line and column of the error, and states what was expected and what was found; `position()` returns
the position alone.

- An error about a whole value, such as an integer that does not fit in 64 bits or an invalid date, is where the value
  starts.
- An error about the definition of a table, such as a table defined twice, is at the `[` of its header.
- An error about a key, such as a duplicate key, is at that key, or at the part of a dotted key it is about, inside a
  header too.

These are the positions `TomlPositions` reports. Any other error is where the parser found it.

## Binding errors

A value that does not bind to a record throws a [`TomlMappingException`](api:be.imgn.toml.TomlMappingException),
whose `path()` tells where the value is: its keys separated by dots, with the index of each element of an array in
brackets, as in `endpoints[0].methods[1]: expected a string, found an integer`. See [Binding to records](binding.md).
