/**
 * Parsing and writing <a href="https://toml.io/en/v1.1.0">TOML 1.1</a> documents.
 *
 * <p>{@link be.imgn.toml.Toml} parses a document into a {@link be.imgn.toml.TomlTable} and writes one back. Every
 * value is a {@link be.imgn.toml.TomlValue}. It also binds a document to a record annotated
 * {@link be.imgn.toml.TomlConfig}, through the code the {@code be.imgn.toml:toml-processor} annotation processor
 * generates.
 */
@NullMarked
package be.imgn.toml;

import org.jspecify.annotations.NullMarked;
