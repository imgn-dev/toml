package be.imgn.toml.processor;

import java.util.List;
import javax.lang.model.element.TypeElement;

/**
 * The binder to generate for a record: a method that binds the record and one that writes it, and the same for each
 * record and enum it refers to.
 *
 * @param records the records, the annotated one first
 * @param enums the enums
 */
record Binder(List<RecordMethod> records, List<EnumMethod> enums) {

    Binder {
        records = List.copyOf(records);
        enums = List.copyOf(enums);
    }

    TypeElement root() {
        return records.getFirst().type();
    }

    /** How a value converts. */
    sealed interface Conversion {}

    /**
     * A conversion that a method of {@code TomlBindings} does.
     *
     * @param name the name of the method, such as {@code asInt}
     */
    record BindingsMethod(String name) implements Conversion {}

    /**
     * A check that a value is of a {@code TomlValue} type.
     *
     * @param qualifiedName the name of the type
     */
    record ValueType(String qualifiedName) implements Conversion {}

    /**
     * A conversion to a list, a set or a map.
     *
     * @param factory the method of {@code TomlBindings} that creates the conversion: {@code list}, {@code set} or
     *     {@code map}
     * @param element how the elements, or the values of the map, convert
     */
    record Container(String factory, Conversion element) implements Conversion {}

    /**
     * A conversion that methods of the binder do.
     *
     * @param name the name of the method that binds
     * @param writer the name of the method that writes
     */
    record BinderMethod(String name, String writer) implements Conversion {}

    /**
     * The methods that bind and write a record.
     *
     * @param type the record
     * @param name the name of the method that binds
     * @param writer the name of the method that writes
     * @param components the components of the record, in order
     */
    record RecordMethod(TypeElement type, String name, String writer, List<Component> components) {

        RecordMethod {
            components = List.copyOf(components);
        }
    }

    /**
     * A component of a record.
     *
     * @param accessor the name of its accessor
     * @param variable the name of the local variable that holds its value
     * @param key its key
     * @param optional whether the key may be missing
     * @param conversion how its value converts
     */
    record Component(String accessor, String variable, String key, boolean optional, Conversion conversion) {}

    /**
     * The methods that bind and write an enum.
     *
     * @param type the enum
     * @param name the name of the method that binds
     * @param writer the name of the method that writes
     * @param constants the constants of the enum, in order
     */
    record EnumMethod(TypeElement type, String name, String writer, List<Constant> constants) {

        EnumMethod {
            constants = List.copyOf(constants);
        }
    }

    /**
     * A constant of an enum.
     *
     * @param key the string it binds from, and is written as
     * @param name its name
     */
    record Constant(String key, String name) {}
}
