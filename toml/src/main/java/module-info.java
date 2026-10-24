import be.imgn.toml.TomlBinder;

/**
 * A TOML 1.1 parser and writer.
 *
 * <p>gaia-core is required transitively: its sources and sinks are part of the signatures this module exports.
 */
module be.imgn.toml {
    requires transitive be.imgn.gaia.core;

    exports be.imgn.toml;
    exports be.imgn.toml.i18n;

    // Toml.parse(..., Class) loads the binders that the annotation processor generates.
    uses TomlBinder;
}
