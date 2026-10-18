package be.imgn.toml;

import static be.imgn.toml.Nulls.nullOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class TomlValueTest {

    @Test
    void stringRejectsUnpairedSurrogate() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TomlString("a\uDC00b"))
                .withMessage("a string cannot contain the unpaired surrogate U+DC00");
        assertThatIllegalArgumentException().isThrownBy(() -> new TomlString("\uD800"));
    }

    @Test
    void stringAcceptsSurrogatePair() {
        assertThat(new TomlString("😀").value()).isEqualTo("😀");
    }

    @Test
    void floatEqualsByComparison() {
        assertThat(new TomlFloat(Double.NaN)).isEqualTo(new TomlFloat(Double.NaN));
        assertThat(new TomlFloat(0.0)).isNotEqualTo(new TomlFloat(-0.0));
    }

    @Test
    void datesRejectYearsWithMoreThanFourDigits() {
        var date = LocalDate.of(10_000, 1, 1);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TomlLocalDate(date))
                .withMessage("the year 10000 is outside 0000 to 9999");
        assertThatIllegalArgumentException().isThrownBy(() -> new TomlLocalDate(LocalDate.of(-1, 1, 1)));
        assertThatIllegalArgumentException().isThrownBy(() -> new TomlLocalDateTime(date.atStartOfDay()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TomlOffsetDateTime(date.atStartOfDay().atOffset(ZoneOffset.UTC)));
    }

    @Test
    void datesAcceptYearZero() {
        assertThat(new TomlLocalDate(LocalDate.of(0, 1, 1)).value().getYear()).isZero();
    }

    @Test
    void offsetDateTimeRejectsOffsetWithSeconds() {
        var dateTime = LocalDateTime.of(1979, 5, 27, 7, 32).atOffset(ZoneOffset.ofHoursMinutesSeconds(1, 0, 30));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TomlOffsetDateTime(dateTime))
                .withMessage("the offset +01:00:30 has seconds");
    }

    @Test
    void valuesRejectNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TomlString(nullOf(String.class)))
                .withMessage("value");
        assertThatNullPointerException()
                .isThrownBy(() -> new TomlOffsetDateTime(nullOf(OffsetDateTime.class)))
                .withMessage("value");
        assertThatNullPointerException()
                .isThrownBy(() -> new TomlLocalDateTime(nullOf(LocalDateTime.class)))
                .withMessage("value");
        assertThatNullPointerException()
                .isThrownBy(() -> new TomlLocalDate(nullOf(LocalDate.class)))
                .withMessage("value");
        assertThatNullPointerException()
                .isThrownBy(() -> new TomlLocalTime(nullOf(LocalTime.class)))
                .withMessage("value");
        assertThatNullPointerException()
                .isThrownBy(() -> new TomlArray(Nulls.<TomlValue>nullList()))
                .withMessage("values");
        assertThatNullPointerException()
                .isThrownBy(() -> new TomlArray(Collections.singletonList(nullOf(TomlValue.class))))
                .withMessage("values cannot contain null, as at index 0");
    }

    @Test
    void arrayOfValues() {
        assertThat(TomlArray.of(new TomlInteger(1), new TomlString("two")).values())
                .containsExactly(new TomlInteger(1), new TomlString("two"));
        assertThat(TomlArray.of().values()).isEmpty();
    }

    @Test
    void arrayOfCopiesItsValues() {
        var values = new TomlValue[] {new TomlInteger(1)};
        var array = TomlArray.of(values);

        values[0] = new TomlInteger(2);

        assertThat(array.values()).containsExactly(new TomlInteger(1));
    }

    @Test
    void arrayOfRejectsNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> TomlArray.of(nullOf(TomlValue[].class)))
                .withMessage("values");
        assertThatNullPointerException()
                .isThrownBy(() -> TomlArray.of(new TomlInteger(1), nullOf(TomlValue.class)))
                .withMessage("values cannot contain null, as at index 1");
    }

    @Test
    void arrayCopiesItsValues() {
        var values = new ArrayList<TomlValue>(List.of(new TomlInteger(1)));
        var array = new TomlArray(values);

        values.add(new TomlInteger(2));

        assertThat(array.values()).containsExactly(new TomlInteger(1));
    }
}
