# Resource bundles

A [`TomlResourceBundle`](api:be.imgn.toml.i18n.TomlResourceBundle), in the package `be.imgn.toml.i18n`, loads a
`ResourceBundle` from a TOML resource. It flattens tables into dotted keys, and its values keep their type:

```toml
# com/example/Messages_fr.toml
title = "Inventaire"
days = ["lundi", "mardi"]

[errors]
not-found = "Aucun article {0}"
retries = 3
```

```java
var messages = (TomlResourceBundle)
        ResourceBundle.getBundle("com.example.Messages", locale, TomlResourceBundle.control());

String notFound = messages.getString("errors.not-found");
String[] days = messages.getStringArray("days");
int retries = messages.getInt("errors.retries");
```

- `getObject` returns a `String`, `Long`, `Double`, `Boolean`, one of the four `java.time` types, or a `String[]` for an
  array of strings. Other arrays are an error, and so are two values whose keys flatten to the same one.
- The typed getters (`getLong`, `getInt`, `getDouble`, `getBoolean`, and the date-time getters) look through the parent
  bundles, as `getObject` does.

## In a named module

A named module cannot pass a control to `getBundle`. It declares the service of its bundles instead, and a provider of
it that extends [`TomlResourceBundleProvider`](api:be.imgn.toml.i18n.TomlResourceBundleProvider). The service is named
after the base name of the bundles, as `ResourceBundle.getBundle` requires: the bundles `com.example.Messages` are
provided by `com.example.spi.MessagesProvider`.

```java
module com.example {
    requires be.imgn.toml;
    uses com.example.spi.MessagesProvider;
    provides com.example.spi.MessagesProvider with com.example.internal.TomlMessagesProvider;
}

public interface MessagesProvider extends ResourceBundleProvider {}

public final class TomlMessagesProvider extends TomlResourceBundleProvider implements MessagesProvider {
    public TomlMessagesProvider() {
        super(TomlMessagesProvider.class.getModule()::getResourceAsStream);
    }
}

ResourceBundle messages = ResourceBundle.getBundle("com.example.Messages", locale);
```

The provider reads the resources through the opener it is given, because a module encapsulates the resources of its
packages: `be.imgn.toml` cannot read `com/example/Messages_fr_BE.toml`, but a method reference created in the module
`com.example` can. The resources must be UTF-8, as TOML requires.
