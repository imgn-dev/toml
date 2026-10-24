package be.imgn.toml.i18n;

import static java.util.Objects.requireNonNull;

import be.imgn.gaia.core.io.ByteSource;
import be.imgn.toml.Toml;
import be.imgn.toml.TomlArray;
import be.imgn.toml.TomlBoolean;
import be.imgn.toml.TomlFloat;
import be.imgn.toml.TomlInteger;
import be.imgn.toml.TomlLocalDate;
import be.imgn.toml.TomlLocalDateTime;
import be.imgn.toml.TomlLocalTime;
import be.imgn.toml.TomlOffsetDateTime;
import be.imgn.toml.TomlString;
import be.imgn.toml.TomlTable;
import be.imgn.toml.TomlValue;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A resource bundle whose resources are the values of a TOML table.
 *
 * <p>Tables are flattened into dotted keys: the document
 *
 * <pre>{@code
 * title = "Inventory"
 *
 * [errors]
 * not-found = "No item {0}"
 * retries = 3
 * }</pre>
 *
 * <p>has the keys {@code title}, {@code errors.not-found} and {@code errors.retries}. Two values whose keys flatten to
 * the same one, such as {@code "a.b" = 1} and {@code a.b = 2}, are an error.
 *
 * <p>The values keep their TOML type: {@link #getObject(String)} returns a {@code String}, a {@code Long}, a
 * {@code Double}, a {@code Boolean}, an {@code OffsetDateTime}, a {@code LocalDateTime}, a {@code LocalDate} or a
 * {@code LocalTime}, and an array of strings is a {@code String[]}, which {@link #getStringArray(String)} returns. Any
 * other array is an error. The typed getters, such as {@link #getLong(String)}, look the value up as
 * {@link #getObject(String)} does, through the parents of the bundle, and check its type.
 *
 * <p>{@link ResourceBundle#getBundle(String, Locale, ResourceBundle.Control)} loads bundles from {@code .toml}
 * resources, such as {@code Messages_fr_BE.toml}, with the control {@link #control()} returns:
 *
 * <pre>{@code
 * ResourceBundle messages = ResourceBundle.getBundle("com.example.Messages", locale, TomlResourceBundle.control());
 * }</pre>
 *
 * <p>A named module cannot pass a control to {@code getBundle}: it loads its bundles through a
 * {@link TomlResourceBundleProvider} instead.
 *
 * @since 0.1.0
 */
public final class TomlResourceBundle extends ResourceBundle {

    private static final String FORMAT = "toml";
    private static final Control CONTROL = new TomlControl();

    private final Map<String, Object> values;

    /**
     * Creates a bundle of the values of a table.
     *
     * @param table the table
     * @throws NullPointerException if {@code table} is null
     * @throws IllegalArgumentException if two values have the same flattened key, or if an array has values that are
     *     not strings
     */
    public TomlResourceBundle(TomlTable table) {
        requireNonNull(table, "table");
        var values = new HashMap<String, Object>();
        flatten(null, table, values);
        this.values = Collections.unmodifiableMap(values);
    }

    /**
     * Returns a control that loads bundles from {@code .toml} resources, which must be UTF-8, as TOML requires. The
     * resource of a bundle is named as {@link ResourceBundle.Control#toResourceName(String, String)} names it, with the
     * suffix {@code toml}: the bundle {@code com.example.Messages} for the locale {@code fr_BE} is the resource
     * {@code com/example/Messages_fr_BE.toml}.
     *
     * <p>A resource that is not valid TOML, or does not make a bundle, fails {@code getBundle} with a
     * {@link MissingResourceException} whose cause tells why.
     *
     * @return the control
     */
    public static ResourceBundle.Control control() {
        return CONTROL;
    }

    /**
     * Returns the integer that is the value of a key.
     *
     * @param key the key
     * @return the value of {@code key}
     * @throws NullPointerException if {@code key} is null
     * @throws MissingResourceException if neither this bundle nor its parents have a value for {@code key}
     * @throws ClassCastException if the value of {@code key} is not an integer
     */
    public long getLong(String key) {
        return require(key, Long.class);
    }

    /**
     * Returns the integer that is the value of a key, as an {@code int}.
     *
     * @param key the key
     * @return the value of {@code key}
     * @throws NullPointerException if {@code key} is null
     * @throws MissingResourceException if neither this bundle nor its parents have a value for {@code key}
     * @throws ClassCastException if the value of {@code key} is not an integer
     * @throws ArithmeticException if the value of {@code key} is out of the range of {@code int}
     */
    public int getInt(String key) {
        var value = getLong(key);
        if (value != (int) value) {
            throw new ArithmeticException("the value of the key " + key + " is " + value + ", out of the range of int");
        }
        return (int) value;
    }

    /**
     * Returns the float that is the value of a key.
     *
     * @param key the key
     * @return the value of {@code key}
     * @throws NullPointerException if {@code key} is null
     * @throws MissingResourceException if neither this bundle nor its parents have a value for {@code key}
     * @throws ClassCastException if the value of {@code key} is not a float
     */
    public double getDouble(String key) {
        return require(key, Double.class);
    }

    /**
     * Returns the boolean that is the value of a key.
     *
     * @param key the key
     * @return the value of {@code key}
     * @throws NullPointerException if {@code key} is null
     * @throws MissingResourceException if neither this bundle nor its parents have a value for {@code key}
     * @throws ClassCastException if the value of {@code key} is not a boolean
     */
    public boolean getBoolean(String key) {
        return require(key, Boolean.class);
    }

    /**
     * Returns the offset date-time that is the value of a key.
     *
     * @param key the key
     * @return the value of {@code key}
     * @throws NullPointerException if {@code key} is null
     * @throws MissingResourceException if neither this bundle nor its parents have a value for {@code key}
     * @throws ClassCastException if the value of {@code key} is not an offset date-time
     */
    public OffsetDateTime getOffsetDateTime(String key) {
        return require(key, OffsetDateTime.class);
    }

    /**
     * Returns the local date-time that is the value of a key.
     *
     * @param key the key
     * @return the value of {@code key}
     * @throws NullPointerException if {@code key} is null
     * @throws MissingResourceException if neither this bundle nor its parents have a value for {@code key}
     * @throws ClassCastException if the value of {@code key} is not a local date-time
     */
    public LocalDateTime getLocalDateTime(String key) {
        return require(key, LocalDateTime.class);
    }

    /**
     * Returns the local date that is the value of a key.
     *
     * @param key the key
     * @return the value of {@code key}
     * @throws NullPointerException if {@code key} is null
     * @throws MissingResourceException if neither this bundle nor its parents have a value for {@code key}
     * @throws ClassCastException if the value of {@code key} is not a local date
     */
    public LocalDate getLocalDate(String key) {
        return require(key, LocalDate.class);
    }

    /**
     * Returns the local time that is the value of a key.
     *
     * @param key the key
     * @return the value of {@code key}
     * @throws NullPointerException if {@code key} is null
     * @throws MissingResourceException if neither this bundle nor its parents have a value for {@code key}
     * @throws ClassCastException if the value of {@code key} is not a local time
     */
    public LocalTime getLocalTime(String key) {
        return require(key, LocalTime.class);
    }

    @Override
    protected @Nullable Object handleGetObject(String key) {
        requireNonNull(key, "key");
        return values.get(key);
    }

    @Override
    protected Set<String> handleKeySet() {
        return values.keySet();
    }

    @Override
    public Enumeration<String> getKeys() {
        var keys = new LinkedHashSet<>(values.keySet());
        if (parent != null) {
            keys.addAll(Collections.list(parent.getKeys()));
        }
        return Collections.enumeration(keys);
    }

    static TomlResourceBundle read(InputStream in) throws IOException {
        return new TomlResourceBundle(Toml.parse(ByteSource.wrap(in.readAllBytes())));
    }

    static String resourceName(String bundleName) {
        return CONTROL.toResourceName(bundleName, FORMAT);
    }

    private <T> T require(String key, Class<T> type) {
        var value = getObject(key);
        if (!type.isInstance(value)) {
            throw new ClassCastException("the value of the key " + key + " is a "
                    + value.getClass().getSimpleName() + ", not a " + type.getSimpleName());
        }
        return type.cast(value);
    }

    private static void flatten(@Nullable String prefix, TomlTable table, Map<String, Object> values) {
        table.entries().forEach((name, value) -> {
            var key = prefix == null ? name : prefix + "." + name;
            if (value instanceof TomlTable subtable) {
                flatten(key, subtable, values);
            } else if (values.putIfAbsent(key, resource(key, value)) != null) {
                throw new IllegalArgumentException("more than one value has the key " + key);
            }
        });
    }

    private static Object resource(String key, TomlValue value) {
        return switch (value) {
            case TomlString(var string) -> string;
            case TomlInteger(var integer) -> integer;
            case TomlFloat(var number) -> number;
            case TomlBoolean(var bool) -> bool;
            case TomlOffsetDateTime(var dateTime) -> dateTime;
            case TomlLocalDateTime(var dateTime) -> dateTime;
            case TomlLocalDate(var date) -> date;
            case TomlLocalTime(var time) -> time;
            case TomlArray(var elements) -> strings(key, elements);
            case TomlTable table -> throw new AssertionError("tables are flattened");
        };
    }

    private static String[] strings(String key, List<TomlValue> elements) {
        var strings = new String[elements.size()];
        for (var i = 0; i < strings.length; i++) {
            if (!(elements.get(i) instanceof TomlString(var string))) {
                throw new IllegalArgumentException("the array of the key " + key + " has a "
                        + elements.get(i).getClass().getSimpleName() + " at index " + i + ": only strings are allowed");
            }
            strings[i] = string;
        }
        return strings;
    }

    private static final class TomlControl extends ResourceBundle.Control {

        @Override
        public List<String> getFormats(String baseName) {
            requireNonNull(baseName, "baseName");
            return List.of(FORMAT);
        }

        @Override
        public @Nullable ResourceBundle newBundle(
                String baseName, Locale locale, String format, ClassLoader loader, boolean reload) throws IOException {
            requireNonNull(baseName, "baseName");
            requireNonNull(locale, "locale");
            requireNonNull(format, "format");
            requireNonNull(loader, "loader");
            if (!format.equals(FORMAT)) {
                throw new IllegalArgumentException("unknown format: " + format);
            }
            var url = loader.getResource(toResourceName(toBundleName(baseName, locale), FORMAT));
            if (url == null) {
                return null;
            }
            var connection = url.openConnection();
            if (reload) {
                // Otherwise a JAR connection may serve the content of the resource as it was before.
                connection.setUseCaches(false);
            }
            try (var in = connection.getInputStream()) {
                return read(in);
            }
        }
    }
}
