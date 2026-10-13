package be.imgn.toml;

import static be.imgn.gaia.core.base.Preconditions.checkArgument;

import java.time.ZoneOffset;

/** The checks that keep every {@link TomlValue} writable as TOML. */
final class Requirements {

    private static final int MAX_YEAR = 9999;
    private static final int SECONDS_PER_MINUTE = 60;

    private Requirements() {}

    static void requireScalarValues(String text, String description) {
        var surrogate = text.codePoints().filter(Requirements::isSurrogate).findFirst();
        checkArgument(
                surrogate.isEmpty(),
                "%s cannot contain the unpaired surrogate U+%04X",
                description,
                surrogate.orElse(0));
    }

    static void requireFourDigitYear(int year) {
        checkArgument(year >= 0 && year <= MAX_YEAR, "the year %s is outside 0000 to 9999", year);
    }

    static void requireWholeMinutes(ZoneOffset offset) {
        checkArgument(offset.getTotalSeconds() % SECONDS_PER_MINUTE == 0, "the offset %s has seconds", offset);
    }

    static String hex(int codePoint) {
        return String.format("%04X", codePoint);
    }

    private static boolean isSurrogate(int codePoint) {
        return codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE;
    }
}
