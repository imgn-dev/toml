# toml

toml is a [TOML 1.1](https://toml.io/en/v1.1.0) parser and writer for Java 25 or later, with no dependency besides
[gaia-core](https://imgn-dev.github.io/gaia/). Every class is documented in the
[API documentation](https://imgn-dev.github.io/toml/api/latest/).

- It accepts every valid document and rejects every invalid one in the
  [toml-test](https://github.com/toml-lang/toml-test) suite (TOML 1.1.0 manifest).
- It parses into immutable values: a sealed hierarchy of records. See [Documents](documents.md).
- It reads from gaia's `CharSource` and `ByteSource`, and writes to its `CharSink` and `ByteSink`.
- It reports errors with their line and column, and tells the line and column of each key and value. See
  [Positions and errors](positions.md).
- It binds documents to records, through code an annotation processor generates: no reflection. See
  [Binding to records](binding.md).
- It loads `ResourceBundle`s from TOML resources, whose values keep their type. See
  [Resource bundles](resource-bundles.md).

## Artifacts

| Artifact         | Module                   | What it does                                                    |
|------------------|--------------------------|-----------------------------------------------------------------|
| `toml`           | `be.imgn.toml`           | [Parses](documents.md), [writes](documents.md#writing) and [binds](binding.md) documents |
| `toml-processor` | `be.imgn.toml.processor` | [Generates the code that binds documents to records](binding.md#setting-up-the-processor) |

## Setting up

```xml
<dependency>
  <groupId>be.imgn.toml</groupId>
  <artifactId>toml</artifactId>
  <version>${toml.version}</version>
</dependency>
```

On the module path, require the module: `requires be.imgn.toml;`. It requires `be.imgn.gaia.core` transitively, as
gaia's sources and sinks are part of its API.

## Nulls

Every package of toml is annotated with JSpecify's `@NullMarked`: a parameter or a return value is never null unless
it is annotated `@Nullable`. A method given null where it doesn't accept it throws `NullPointerException`, naming the
parameter.
