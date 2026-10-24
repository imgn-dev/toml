package be.imgn.toml.i18n;

import static java.util.Objects.requireNonNull;

import be.imgn.toml.TomlParseException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.spi.AbstractResourceBundleProvider;
import org.jspecify.annotations.Nullable;

/**
 * Provides {@link TomlResourceBundle}s to a named module, which cannot load them with
 * {@link TomlResourceBundle#control()}.
 *
 * <p>The module declares the service of its bundles, and a provider of it that extends this class. The service is named
 * after the base name of the bundles, as {@link ResourceBundle#getBundle(String, Locale)} requires: the bundles
 * {@code com.example.Messages} are provided by {@code com.example.spi.MessagesProvider}.
 *
 * <pre>{@code
 * module com.example {
 *     requires be.imgn.toml;
 *     uses com.example.spi.MessagesProvider;
 *     provides com.example.spi.MessagesProvider with com.example.internal.TomlMessagesProvider;
 * }
 *
 * public interface MessagesProvider extends ResourceBundleProvider {}
 *
 * public final class TomlMessagesProvider extends TomlResourceBundleProvider implements MessagesProvider {
 *     public TomlMessagesProvider() {
 *         super(TomlMessagesProvider.class.getModule()::getResourceAsStream);
 *     }
 * }
 *
 * ResourceBundle messages = ResourceBundle.getBundle("com.example.Messages", locale);
 * }</pre>
 *
 * <p>The provider reads the resources through the opener it is given, because a module encapsulates the resources of
 * its packages: {@code be.imgn.toml} cannot read {@code com/example/Messages_fr_BE.toml}, but a method reference
 * created in the module {@code com.example} can.
 *
 * @since 0.1.0
 */
public abstract class TomlResourceBundleProvider extends AbstractResourceBundleProvider {

    private final Opener opener;

    /**
     * Creates a provider.
     *
     * @param opener opens the resources of the bundles, which must be UTF-8, as TOML requires
     * @throws NullPointerException if {@code opener} is null
     */
    protected TomlResourceBundleProvider(Opener opener) {
        this.opener = requireNonNull(opener, "opener");
    }

    /**
     * Returns the bundle of a locale, read from the {@code .toml} resource of its bundle name: the bundle
     * {@code com.example.Messages} for the locale {@code fr_BE} is the resource
     * {@code com/example/Messages_fr_BE.toml}.
     *
     * @param baseName the base name of the bundle
     * @param locale the locale of the bundle
     * @return the bundle, or null if it has no resource
     * @throws NullPointerException if an argument is null
     * @throws UncheckedIOException if reading the resource fails
     * @throws TomlParseException if the resource is not valid UTF-8, or not valid TOML
     * @throws IllegalArgumentException if the resource does not make a {@link TomlResourceBundle}
     */
    @Override
    public @Nullable ResourceBundle getBundle(String baseName, Locale locale) {
        requireNonNull(baseName, "baseName");
        requireNonNull(locale, "locale");
        var name = TomlResourceBundle.resourceName(toBundleName(baseName, locale));
        try (var in = opener.open(name)) {
            return in == null ? null : TomlResourceBundle.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + name, e);
        }
    }

    /**
     * Opens resources, with the access of the module that provides the bundles.
     *
     * @since 0.1.0
     */
    @FunctionalInterface
    public interface Opener {

        /**
         * Opens a resource.
         *
         * @param name the name of the resource, such as {@code com/example/Messages_fr_BE.toml}
         * @return a stream of the content of the resource, or null if there is no such resource
         * @throws IOException if opening the resource fails
         * @see Module#getResourceAsStream(String)
         */
        @Nullable
        InputStream open(String name) throws IOException;
    }
}
