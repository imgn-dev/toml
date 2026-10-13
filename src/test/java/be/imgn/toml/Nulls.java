package be.imgn.toml;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Nulls for testing the null checks of the public API, which NullAway rejects when they are written as literals. */
final class Nulls {

    private Nulls() {}

    /**
     * Returns null, as code that NullAway does not check could pass it. NullAway trusts the return values of the JDK's
     * unannotated methods, such as {@link Collections#singletonList(Object)}.
     */
    static <T> T nullOf(Class<T> type) {
        return type.cast(Collections.singletonList(null).getFirst());
    }

    static <K, V> Map<K, V> nullMap() {
        return Collections.<Map<K, V>>singletonList(null).getFirst();
    }

    static <E> List<E> nullList() {
        return Collections.<List<E>>singletonList(null).getFirst();
    }
}
