package be.imgn.toml;

import static be.imgn.toml.Nulls.nullOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class TomlPositionsTest {

    private static final String DOCUMENT = """
            title = "Inventory"

            [server]
            host = "0.0.0.0"
            port = 8080
            log.level = "info"

            [commands.save]
            label = "Save"
            keys = ["ctrl S", "meta S"]

            [[endpoints]]
            path = "/items"

            [[endpoints]]
            path = "/orders"
            limits = { rate = 10 }
            """;

    private final TomlDocument document = Toml.parse(DOCUMENT);
    private final TomlTable root = document.root();
    private final TomlPositions positions = document.positions();

    @Test
    void locatesKeysAndValues() {
        assertThat(positions.key(root, "title")).isEqualTo(new TomlPosition(1, 1));
        assertThat(positions.value(root, "title")).isEqualTo(new TomlPosition(1, 9));
        assertThat(positions.value(root.getTable("server"), "port")).isEqualTo(new TomlPosition(5, 8));
    }

    @Test
    void locatesTableDefinedByHeaderAtItsKeyAndHeader() {
        assertThat(positions.key(root, "server")).isEqualTo(new TomlPosition(3, 2));
        assertThat(positions.value(root, "server")).isEqualTo(new TomlPosition(3, 1));

        var commands = root.getTable("commands");
        assertThat(positions.key(commands, "save")).isEqualTo(new TomlPosition(8, 11));
        assertThat(positions.value(commands, "save")).isEqualTo(new TomlPosition(8, 1));
        var save = commands.getTable("save");
        assertThat(positions.key(save, "label")).isEqualTo(new TomlPosition(9, 1));
        assertThat(positions.value(save, "label")).isEqualTo(new TomlPosition(9, 9));
    }

    @Test
    void locatesImplicitTableAtItsKey() {
        assertThat(positions.key(root, "commands")).isEqualTo(new TomlPosition(8, 2));
        assertThat(positions.value(root, "commands")).isEqualTo(new TomlPosition(8, 2));
    }

    @Test
    void locatesEachPartOfDottedKey() {
        var server = root.getTable("server");
        assertThat(positions.key(server, "log")).isEqualTo(new TomlPosition(6, 1));
        assertThat(positions.value(server, "log")).isEqualTo(new TomlPosition(6, 1));
        var log = server.getTable("log");
        assertThat(positions.key(log, "level")).isEqualTo(new TomlPosition(6, 5));
        assertThat(positions.value(log, "level")).isEqualTo(new TomlPosition(6, 13));
    }

    @Test
    void locatesElementsOfArray() {
        var save = root.getTable("commands.save");
        var keys = save.getArray("keys");
        assertThat(positions.value(save, "keys")).isEqualTo(new TomlPosition(10, 8));
        assertThat(positions.element(keys, 0)).isEqualTo(new TomlPosition(10, 9));
        assertThat(positions.element(keys, 1)).isEqualTo(new TomlPosition(10, 19));
    }

    @Test
    void locatesArrayOfTablesAtItsHeaders() {
        var endpoints = root.getArray("endpoints");
        assertThat(positions.key(root, "endpoints")).isEqualTo(new TomlPosition(12, 3));
        assertThat(positions.value(root, "endpoints")).isEqualTo(new TomlPosition(12, 1));
        assertThat(positions.element(endpoints, 0)).isEqualTo(new TomlPosition(12, 1));
        assertThat(positions.element(endpoints, 1)).isEqualTo(new TomlPosition(15, 1));

        var second = (TomlTable) endpoints.values().get(1);
        assertThat(positions.value(second, "path")).isEqualTo(new TomlPosition(16, 8));
    }

    @Test
    void locatesEntriesOfInlineTable() {
        var second = (TomlTable) root.getArray("endpoints").values().get(1);
        assertThat(positions.value(second, "limits")).isEqualTo(new TomlPosition(17, 10));
        var limits = second.getTable("limits");
        assertThat(positions.key(limits, "rate")).isEqualTo(new TomlPosition(17, 12));
        assertThat(positions.value(limits, "rate")).isEqualTo(new TomlPosition(17, 19));
    }

    @Test
    void locatesTablesAndArraysNestedInArrays() {
        var document = Toml.parse("a = [[1, 2], [3]]\np = [{ x = 1 }]");
        var root = document.root();
        var positions = document.positions();

        var a = root.getArray("a");
        assertThat(positions.element(a, 0)).isEqualTo(new TomlPosition(1, 6));
        assertThat(positions.element(a, 1)).isEqualTo(new TomlPosition(1, 14));
        assertThat(positions.element((TomlArray) a.values().getFirst(), 1)).isEqualTo(new TomlPosition(1, 10));
        var p = (TomlTable) root.getArray("p").values().getFirst();
        assertThat(positions.key(p, "x")).isEqualTo(new TomlPosition(2, 8));
    }

    @Test
    void locatesTableAtTheHeaderThatDefinesIt() {
        var document = Toml.parse("""
                [a.b]
                x = 1
                [a]
                y = 2
                """);

        assertThat(document.positions().key(document.root(), "a")).isEqualTo(new TomlPosition(3, 2));
        assertThat(document.positions().value(document.root(), "a")).isEqualTo(new TomlPosition(3, 1));
    }

    @Test
    void locatesDottedTableWhereItFirstAppears() {
        var document = Toml.parse("a.b = 1\na.c = 2");

        assertThat(document.positions().key(document.root(), "a")).isEqualTo(new TomlPosition(1, 1));
        assertThat(document.positions().key(document.root().getTable("a"), "c")).isEqualTo(new TomlPosition(2, 3));
    }

    @Test
    void locatesQuotedKeysAndKeysAfterWhitespace() {
        var document = Toml.parse("\"a b\".'c' = 1\n  d . e = 2");
        var root = document.root();
        var positions = document.positions();

        assertThat(positions.key(root, "a b")).isEqualTo(new TomlPosition(1, 1));
        assertThat(positions.key(root.getTable("\"a b\""), "c")).isEqualTo(new TomlPosition(1, 7));
        assertThat(positions.key(root, "d")).isEqualTo(new TomlPosition(2, 3));
        assertThat(positions.key(root.getTable("d"), "e")).isEqualTo(new TomlPosition(2, 7));
    }

    @Test
    void countsCarriageReturnAndLineFeedAsOneLineEnd() {
        var document = Toml.parse("a = 1\r\nb = 2");

        assertThat(document.positions().key(document.root(), "b")).isEqualTo(new TomlPosition(2, 1));
        assertThat(document.positions().value(document.root(), "b")).isEqualTo(new TomlPosition(2, 5));
    }

    @Test
    void doesNotCountByteOrderMark() {
        var document = Toml.parse("﻿a = 1");

        assertThat(document.positions().key(document.root(), "a")).isEqualTo(new TomlPosition(1, 1));
        assertThat(document.positions().value(document.root(), "a")).isEqualTo(new TomlPosition(1, 5));
    }

    @Test
    void countsLinesOfMultilineStrings() {
        var document = Toml.parse("a = \"\"\"\nx\ny\"\"\"\nb = 1");

        assertThat(document.positions().key(document.root(), "b")).isEqualTo(new TomlPosition(4, 1));
    }

    @Test
    void rejectsTableOrArrayNotOfDocument() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> positions.key(root.toBuilder().build(), "title"))
                .withMessage("the table is not a table of this document");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> positions.value(Toml.parse(DOCUMENT).root(), "title"))
                .withMessage("the table is not a table of this document");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> positions.element(TomlArray.of(new TomlInteger(1)), 0))
                .withMessage("the array is not an array of this document");
    }

    @Test
    void rejectsMissingKeyOrIndex() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> positions.key(root, "missing"))
                .withMessage("the table has no key 'missing'");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> positions.value(root, "server.port"))
                .withMessage("the table has no key 'server.port'");
        assertThatExceptionOfType(IndexOutOfBoundsException.class)
                .isThrownBy(() -> positions.element(root.getArray("endpoints"), 2));
    }

    @Test
    void rejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> positions.key(nullOf(TomlTable.class), "title"));
        assertThatNullPointerException().isThrownBy(() -> positions.value(root, nullOf(String.class)));
        assertThatNullPointerException().isThrownBy(() -> positions.element(nullOf(TomlArray.class), 0));
        assertThatNullPointerException().isThrownBy(() -> new TomlDocument(nullOf(TomlTable.class), positions));
        assertThatNullPointerException().isThrownBy(() -> new TomlDocument(root, nullOf(TomlPositions.class)));
    }

    @Test
    void comparesDocumentsByTheirPositions() {
        var again = Toml.parse(DOCUMENT);

        assertThat(again).isNotEqualTo(document);
        assertThat(again.root()).isEqualTo(root);
        assertThat(new TomlDocument(root, positions)).isEqualTo(document);
    }

    @Test
    void rejectsPositionBeforeFirstLineOrColumn() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TomlPosition(0, 1))
                .withMessage("the line 0 is less than 1");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TomlPosition(1, 0))
                .withMessage("the column 0 is less than 1");
    }
}
