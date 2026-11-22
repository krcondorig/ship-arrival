package com.shiparrival.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las etiquetas que ve el usuario final nunca deben exponer el nombre crudo del
 * enum. Estas pruebas iteran sobre {@link Carrier#values()} para que una
 * naviera nueva quede cubierta automáticamente.
 */
class CarrierTest {

    @Test
    @DisplayName("Todas las navieras tienen una etiqueta no nula y no vacía")
    void everyCarrierHasNonBlankLabel() {
        assertThat(Carrier.values()).allSatisfy(carrier -> {
            assertThat(carrier.getLabel())
                    .as("etiqueta de %s", carrier)
                    .isNotNull()
                    .isNotBlank();
        });
    }

    @Test
    @DisplayName("Ninguna etiqueta filtra el nombre crudo del enum")
    void noLabelLeaksRawEnumName() {
        assertThat(Carrier.values()).allSatisfy(carrier ->
                assertThat(carrier.getLabel())
                        .as("etiqueta de %s no debe contener guiones bajos", carrier)
                        .doesNotContain("_"));
    }

    @Test
    @DisplayName("Solo los acrónimos conservan su nombre; el resto tiene texto propio")
    void onlyAcronymsKeepTheirEnumName() {
        // ONE/MSC/MAERSK son acrónimos: su etiqueta es legítimamente igual al
        // nombre del enum. Cualquier otra naviera debe tener texto propio.
        assertThat(Carrier.values())
                .filteredOn(carrier -> carrier.getLabel().equals(carrier.name()))
                .containsExactlyInAnyOrder(Carrier.ONE, Carrier.MSC, Carrier.MAERSK);
    }

    @Test
    @DisplayName("Cada naviera tiene el texto exacto que se muestra en la UI")
    void labelsMatchExpectedText() {
        assertThat(Carrier.ONE.getLabel()).isEqualTo("ONE");
        assertThat(Carrier.MSC.getLabel()).isEqualTo("MSC");
        assertThat(Carrier.MAERSK.getLabel()).isEqualTo("MAERSK");
        assertThat(Carrier.HAPAG_LLOYD.getLabel()).isEqualTo("Hapag-Lloyd");
        assertThat(Carrier.UNKNOWN.getLabel()).isEqualTo("Desconocida");
    }

    @Test
    @DisplayName("La etiqueta en minúsculas coincide con la clase CSS del badge")
    void labelMatchesCssClassName() {
        // history.html colorea el badge con '.carrier-badge.<lowercase(label)>'.
        assertThat(Carrier.ONE.getLabel().toLowerCase()).isEqualTo("one");
        assertThat(Carrier.MSC.getLabel().toLowerCase()).isEqualTo("msc");
        assertThat(Carrier.MAERSK.getLabel().toLowerCase()).isEqualTo("maersk");
        assertThat(Carrier.HAPAG_LLOYD.getLabel().toLowerCase()).isEqualTo("hapag-lloyd");
    }

    @Test
    @DisplayName("Las constantes persistidas conservan su nombre exacto")
    void persistedNamesAreUnchanged() {
        assertThat(Arrays.stream(Carrier.values()).map(Enum::name))
                .containsExactly("ONE", "MSC", "MAERSK", "HAPAG_LLOYD", "UNKNOWN");
    }
}
