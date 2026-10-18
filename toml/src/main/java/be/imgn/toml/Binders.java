package be.imgn.toml;

import java.util.ServiceLoader;

/** Finds the {@link TomlBinder} of a record, once per record. */
final class Binders {

    private static final ClassValue<TomlBinder<?>> BINDERS = new ClassValue<>() {
        @Override
        protected TomlBinder<?> computeValue(Class<?> type) {
            // The class loader of the record also loads its binder, which the processor generates next to it.
            for (TomlBinder<?> binder : ServiceLoader.load(TomlBinder.class, type.getClassLoader())) {
                if (binder.type() == type) {
                    return binder;
                }
            }
            throw new IllegalArgumentException("no TomlBinder is registered for " + type.getName()
                    + ": annotate it with @TomlConfig, and compile it with the be.imgn.toml:toml-processor annotation"
                    + " processor; on the module path, its module must also provide be.imgn.toml.TomlBinder with the"
                    + " binder generated for it, which the register-services goal of be.imgn.gaia:gaia-maven-plugin"
                    + " adds");
        }
    };

    private Binders() {}

    static TomlBinder<?> of(Class<?> type) {
        return BINDERS.get(type);
    }
}
