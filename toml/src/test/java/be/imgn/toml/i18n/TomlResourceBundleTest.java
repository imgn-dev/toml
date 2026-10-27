package be.imgn.toml.i18n;

import static be.imgn.toml.Nulls.nullOf;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import be.imgn.toml.Toml;
import be.imgn.toml.TomlParseException;
import be.imgn.toml.TomlTable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import org.junit.jupiter.api.Test;

class TomlResourceBundleTest {

    private static final TomlResourceBundle BUNDLE =
            new TomlResourceBundle(Toml.parse("""
            string = "text"
            integer = 42
            float = 3.5
            boolean = true
            offset-date-time = 1979-05-27T07:32:00Z
            local-date-time = 1979-05-27T07:32:00
            local-date = 1979-05-27
            local-time = 07:32:00
            strings = ["a", "b"]
            empty = []

            [errors]
            not-found = "No item {0}"
            "host.name" = "example.com"
            limits = { max = 2147483648 }
            """).root());

    @Test
    void flattensTablesIntoDottedKeys() {
        assertThat(BUNDLE.keySet())
                .containsExactlyInAnyOrder(
                        "string",
                        "integer",
                        "float",
                        "boolean",
                        "offset-date-time",
                        "local-date-time",
                        "local-date",
                        "local-time",
                        "strings",
                        "empty",
                        "errors.not-found",
                        "errors.host.name",
                        "errors.limits.max");
        assertThat(Collections.list(BUNDLE.getKeys())).containsExactlyInAnyOrderElementsOf(BUNDLE.keySet());
        assertThat(BUNDLE.getString("errors.not-found")).isEqualTo("No item {0}");
        assertThat(BUNDLE.getString("errors.host.name")).isEqualTo("example.com");
    }

    @Test
    void keepsTypesOfValues() {
        assertThat(BUNDLE.getObject("string")).isEqualTo("text");
        assertThat(BUNDLE.getObject("integer")).isEqualTo(42L);
        assertThat(BUNDLE.getObject("float")).isEqualTo(3.5);
        assertThat(BUNDLE.getObject("boolean")).isEqualTo(true);
        assertThat(BUNDLE.getObject("local-date")).isEqualTo(LocalDate.of(1979, 5, 27));
        assertThat(BUNDLE.getStringArray("strings")).containsExactly("a", "b");
        assertThat(BUNDLE.getStringArray("empty")).isEmpty();
    }

    @Test
    void getsTypedValues() {
        assertThat(BUNDLE.getLong("integer")).isEqualTo(42);
        assertThat(BUNDLE.getInt("integer")).isEqualTo(42);
        assertThat(BUNDLE.getDouble("float")).isEqualTo(3.5);
        assertThat(BUNDLE.getBoolean("boolean")).isTrue();
        assertThat(BUNDLE.getOffsetDateTime("offset-date-time"))
                .isEqualTo(OffsetDateTime.of(1979, 5, 27, 7, 32, 0, 0, ZoneOffset.UTC));
        assertThat(BUNDLE.getLocalDateTime("local-date-time")).isEqualTo(LocalDateTime.of(1979, 5, 27, 7, 32));
        assertThat(BUNDLE.getLocalDate("local-date")).isEqualTo(LocalDate.of(1979, 5, 27));
        assertThat(BUNDLE.getLocalTime("local-time")).isEqualTo(LocalTime.of(7, 32));
    }

    @Test
    void typedGetterRejectsMissingKey() {
        assertThatExceptionOfType(MissingResourceException.class)
                .isThrownBy(() -> BUNDLE.getLong("missing"))
                .satisfies(e -> assertThat(e.getKey()).isEqualTo("missing"));
    }

    @Test
    void typedGetterRejectsOtherType() {
        assertThatExceptionOfType(ClassCastException.class)
                .isThrownBy(() -> BUNDLE.getLong("string"))
                .withMessage("the value of the key string is a String, not a Long");
        assertThatExceptionOfType(ClassCastException.class).isThrownBy(() -> BUNDLE.getString("integer"));
    }

    @Test
    void intGetterRejectsIntegerOutOfRange() {
        assertThatExceptionOfType(ArithmeticException.class)
                .isThrownBy(() -> BUNDLE.getInt("errors.limits.max"))
                .withMessage("the value of the key errors.limits.max is 2147483648, out of the range of int");
    }

    @Test
    void rejectsValuesWithSameFlattenedKey() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TomlResourceBundle(Toml.parse("""
                        "a.b" = 1
                        a.b = 2
                        """).root()))
                .withMessage("more than one value has the key a.b");
    }

    @Test
    void rejectsArrayOfOtherValuesThanStrings() {
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        new TomlResourceBundle(Toml.parse("a = [\"one\", 2]").root()))
                .withMessage("the array of the key a has a TomlInteger at index 1: only strings are allowed");
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new TomlResourceBundle(Toml.parse("[[a]]\nb = 1").root()))
                .withMessage("the array of the key a has a TomlTable at index 0: only strings are allowed");
    }

    @Test
    void rejectsNullArguments() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TomlResourceBundle(nullOf(TomlTable.class)))
                .withMessage("table");
        assertThatNullPointerException().isThrownBy(() -> BUNDLE.getObject(nullOf(String.class)));
        assertThatNullPointerException().isThrownBy(() -> BUNDLE.getLong(nullOf(String.class)));
    }

    @Test
    void controlLoadsOnlyTomlResources() {
        assertThat(TomlResourceBundle.control().getFormats("messages")).containsExactly("toml");
    }

    @Test
    void providerReadsBundleThroughItsOpener() {
        var provider = new MapProvider(Map.of("com/example/Messages_fr.toml", "title = \"Inventaire\""));

        var bundle = requireNonNull(provider.getBundle("com.example.Messages", Locale.FRENCH));

        assertThat(bundle).isInstanceOf(TomlResourceBundle.class);
        assertThat(bundle.getString("title")).isEqualTo("Inventaire");
    }

    @Test
    void providerHasNoBundleWithoutResource() {
        assertThat(new MapProvider(Map.of()).getBundle("com.example.Messages", Locale.FRENCH))
                .isNull();
    }

    @Test
    void providerPropagatesInvalidResource() {
        var provider = new MapProvider(Map.of("messages.toml", "title ="));

        assertThatExceptionOfType(TomlParseException.class)
                .isThrownBy(() -> provider.getBundle("messages", Locale.ROOT));
    }

    @Test
    void providerWrapsFailureToReadResource() {
        var provider = new TomlResourceBundleProvider(name -> {
            throw new IOException("unreadable");
        }) {};

        assertThatExceptionOfType(UncheckedIOException.class)
                .isThrownBy(() -> provider.getBundle("messages", Locale.ROOT))
                .withMessage("cannot read messages.toml")
                .havingCause()
                .withMessage("unreadable");
    }

    @Test
    void providerRejectsNullArguments() {
        var provider = new MapProvider(Map.of());

        assertThatNullPointerException()
                .isThrownBy(() -> new TomlResourceBundleProvider(nullOf(TomlResourceBundleProvider.Opener.class)) {})
                .withMessage("opener");
        assertThatNullPointerException()
                .isThrownBy(() -> provider.getBundle(nullOf(String.class), Locale.ROOT))
                .withMessage("baseName");
        assertThatNullPointerException()
                .isThrownBy(() -> provider.getBundle("messages", nullOf(Locale.class)))
                .withMessage("locale");
    }

    private static final class MapProvider extends TomlResourceBundleProvider {

        MapProvider(Map<String, String> resources) {
            super(name -> {
                var content = resources.get(name);
                return content == null ? null : new ByteArrayInputStream(content.getBytes(UTF_8));
            });
        }
    }
}
