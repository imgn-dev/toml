package be.imgn.toml.benchmarks;

import java.util.Locale;

/// The documents the benchmarks parse, which are generated, always the same: an application's configuration,
/// catalogs of products of two sizes, and documents of numbers and of strings alone.
final class Documents {

    private static final int MEDIUM_PRODUCTS = 120;
    private static final int LARGE_PRODUCTS = 5000;
    private static final int NUMBER_ENTRIES = 6000;
    private static final int STRING_ENTRIES = 4000;
    private static final int ARRAY_EVERY = 10;
    private static final int ARRAY_SIZE = 16;
    private static final int STRING_KINDS = 4;
    // The values of the products and the numbers cycle through ranges of these sizes, so that few are alike.
    private static final int PRICES = 1000;
    private static final int CENTS = 100;
    private static final int QUANTITY_STEP = 37;
    private static final int QUANTITIES = 100_000;
    private static final int TAGS = 13;
    private static final int FIRST_YEAR = 2010;
    private static final int YEARS = 15;
    private static final int MONTHS = 12;
    private static final int DAYS = 28;
    private static final int MINUTES = 60;
    private static final int SIZES = 10;
    private static final int FRACTIONS = 10_000;
    private static final int EXPONENTS = 7;
    private static final int ELEMENTS = 1_000_000;
    // Knuth's multiplicative hash, which spreads consecutive integers over 32 bits.
    private static final int HASH_MULTIPLIER = (int) 2_654_435_761L;

    private Documents() {}

    /// Returns the document of a name, which is a value of the benchmarks' `document` parameter.
    static String get(String name) {
        return switch (name) {
            case "small" -> small();
            case "medium" -> products(MEDIUM_PRODUCTS);
            case "large" -> products(LARGE_PRODUCTS);
            case "numbers" -> numbers();
            case "strings" -> strings();
            default -> throw new IllegalArgumentException("no document " + name);
        };
    }

    /// An application's configuration, of about 1 KB, with most kinds of value.
    private static String small() {
        return """
                # Application configuration
                title = "Inventory service"
                version = "2.4.1"
                debug = false

                [server]
                host = "0.0.0.0"
                port = 8080
                timeout = "PT30S"
                log-level = "info"   # one of debug, info, warn
                allowed-origins = ["https://example.com", "https://www.example.com", "http://localhost:3000"]

                [database]
                url = "jdbc:postgresql://db.internal:5432/inventory"
                user = "inventory"
                pool-size = 16
                connect-timeout = "PT5S"
                options = { ssl = true, application-name = "inventory-service", fetch-size = 500 }

                [cache]
                enabled = true
                max-entries = 10_000
                expire-after = "PT10M"

                [[endpoints]]
                path = "/items"
                methods = ["GET", "POST"]
                rate-limit = 120

                [[endpoints]]
                path = "/items/{id}"
                methods = ["GET", "PUT", "DELETE"]
                rate-limit = 60

                [[endpoints]]
                path = "/health"
                methods = ["GET"]
                rate-limit = 1_000

                [owner]
                name = "Tom Preston-Werner"
                since = 1979-05-27T07:32:00-08:00
                description = '''
                Owns the service, and answers its pages.
                Escalate to the platform team after hours.'''
                """;
    }

    /// A catalog of products, an array of tables of about 340 bytes each.
    private static String products(int count) {
        var out = new StringBuilder();
        out.append("""
                # Inventory
                title = "Inventory of the warehouse"

                [owner]
                name = "Tom Preston-Werner"
                dob = 1979-05-27T07:32:00-08:00

                """);
        for (var i = 0; i < count; i++) {
            out.append(String.format(
                    Locale.ROOT,
                    """
                    [[products]]
                    name = "Product %1$d"
                    sku = "SKU-%1$06d"
                    price = %2$d.%3$02d
                    quantity = %4$d
                    tags = ["tag-%5$d", "tag-%6$d", "sale"]
                    released = %7$d-%8$02d-%9$02d
                    updated = 2024-%8$02d-%9$02dT10:%10$02d:00Z
                    dimensions = { width = %11$d.5, height = %12$d.25, depth = %11$d }  # centimeters
                    description = "A product with a \\"quoted\\" word, caf\\u00e9 and déjà vu, line %1$d."
                    active = %13$b

                    """,
                    i,
                    i % PRICES,
                    i % CENTS,
                    i * QUANTITY_STEP % QUANTITIES,
                    i % TAGS,
                    (i + 1) % TAGS,
                    FIRST_YEAR + i % YEARS,
                    1 + i % MONTHS,
                    1 + i % DAYS,
                    i % MINUTES,
                    1 + i % SIZES,
                    1 + (i + 1) % SIZES,
                    i % 2 == 0));
        }
        return out.toString();
    }

    /// Integers, floats and arrays of integers, in one table.
    private static String numbers() {
        var out = new StringBuilder("[numbers]\n");
        for (var i = 0; i < NUMBER_ENTRIES; i++) {
            var n = Integer.toUnsignedLong(i * HASH_MULTIPLIER);
            out.append(String.format(
                    Locale.ROOT, "i%d = %d\nf%1$d = %d.%de-%d\n", i, n, n % QUANTITIES, n % FRACTIONS, i % EXPONENTS));
            if (i % ARRAY_EVERY == 0) {
                out.append('a').append(i).append(" = [");
                for (var j = 0; j < ARRAY_SIZE; j++) {
                    out.append(j == 0 ? "" : ", ").append((n >>> j) % ELEMENTS);
                }
                out.append("]\n");
            }
        }
        return out.toString();
    }

    /// Basic strings with and without escapes, literal strings and multi-line strings, with non-ASCII text.
    private static String strings() {
        var out = new StringBuilder("[strings]\n");
        for (var i = 0; i < STRING_ENTRIES; i++) {
            out.append(stringEntry(i % STRING_KINDS).formatted(i));
        }
        return out.toString();
    }

    /// Returns the format of an entry of [#strings()], of a kind from 0 to 3, which takes its number.
    private static String stringEntry(int kind) {
        return switch (kind) {
            case 0 -> "s%d = \"Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor %1$d\"\n";
            case 1 -> "e%d = \"tab\\there, quote \\\" and backslash \\\\, then \\u00e9t\\u00e9 %1$d\"\n";
            case 2 -> "l%d = 'C:\\Users\\nodejs\\templates\\%1$d — Ünïcödé text, 東京, Ελληνικά'\n";
            default ->
                "m%d = \"\"\"\nThe quick brown fox jumps over the lazy dog %1$d.\n"
                        + "Second line of the multi-line string.\"\"\"\n";
        };
    }
}
