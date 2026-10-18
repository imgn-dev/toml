package be.imgn.toml;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.CLASS;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Gives the key of a record component, or the string of an enum constant, in place of its name in kebab case.
 *
 * <pre>{@code
 * record Database(@TomlKey("jdbc_url") String url) {}
 *
 * enum Level {
 *     @TomlKey("warn") WARNING,
 *     ERROR
 * }
 * }</pre>
 *
 * @see TomlConfig
 * @since 0.1.0
 */
@Documented
@Retention(CLASS)
@Target({RECORD_COMPONENT, FIELD})
public @interface TomlKey {

    /**
     * Returns the key, taken as is: a dot is part of the key and does not nest tables.
     *
     * @return the key
     */
    String value();
}
