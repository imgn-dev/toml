/// Parsing and writing [TOML 1.1](https://toml.io/en/v1.1.0) documents.
///
/// [be.imgn.toml.Toml] parses a document into a [be.imgn.toml.TomlDocument], whose root
/// [be.imgn.toml.TomlTable] it writes back, and whose [be.imgn.toml.TomlPositions] tell where its keys and
/// values are written. Every value is a [be.imgn.toml.TomlValue]. It also binds a document to a record
/// annotated [be.imgn.toml.TomlConfig], through the code the `be.imgn.toml:toml-processor` annotation processor
/// generates.
@NullMarked
package be.imgn.toml;

import org.jspecify.annotations.NullMarked;
