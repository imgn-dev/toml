package be.imgn.toml.processor;

import java.util.List;
import javax.lang.model.element.TypeElement;

/**
 * The binder to generate for a record: a method for the record, and one for each record and enum it refers to.
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
     * A conversion that a method of the binder does.
     *
     * @param name the name of the method
     */
    record BinderMethod(String name) implements Conversion {}

    /**
     * The method that binds a record.
     *
     * @param type the record
     * @param name the name of the method
     * @param components the components of the record, in order
     */
    record RecordMethod(TypeElement type, String name, List<Component> components) {

        RecordMethod {
            components = List.copyOf(components);
        }
    }

    /**
     * A component of a record.
     *
     * @param variable the name of the local variable that holds its value
     * @param key its key
     * @param optional whether the key may be missing
     * @param conversion how its value converts
     */
    record Component(String variable, String key, boolean optional, Conversion conversion) {}

    /**
     * The method that binds an enum.
     *
     * @param type the enum
     * @param name the name of the method
     * @param constants the constants of the enum, in order
     */
    record EnumMethod(TypeElement type, String name, List<Constant> constants) {

        EnumMethod {
            constants = List.copyOf(constants);
        }
    }

    /**
     * A constant of an enum.
     *
     * @param key the string it binds from
     * @param name its name
     */
    record Constant(String key, String name) {}
}
