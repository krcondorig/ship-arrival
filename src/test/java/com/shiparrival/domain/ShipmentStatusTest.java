package com.shiparrival.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las etiquetas que ve el usuario final nunca deben exponer el nombre crudo del
 * enum. Estas pruebas iteran sobre {@link ShipmentStatus#values()} para que un
 * estado nuevo quede cubierto automáticamente.
 */
class ShipmentStatusTest {

    @Test
    @DisplayName("Todos los estados tienen una etiqueta no nula y no vacía")
    void everyStatusHasNonBlankLabel() {
        assertThat(ShipmentStatus.values()).allSatisfy(status -> {
            assertThat(status.getLabel())
                    .as("etiqueta de %s", status)
                    .isNotNull()
                    .isNotBlank();
        });
    }

    @Test
    @DisplayName("Ninguna etiqueta filtra el nombre crudo del enum")
    void noLabelLeaksRawEnumName() {
        assertThat(ShipmentStatus.values()).allSatisfy(status ->
                assertThat(status.getLabel())
                        .as("etiqueta de %s no debe contener guiones bajos", status)
                        .doesNotContain("_"));
    }

    @Test
    @DisplayName("Ninguna etiqueta repite el nombre del enum")
    void noLabelEqualsEnumName() {
        assertThat(ShipmentStatus.values()).allSatisfy(status ->
                assertThat(status.getLabel())
                        .as("etiqueta de %s no debe ser su nombre de constante", status)
                        .isNotEqualTo(status.name()));
    }

    @Test
    @DisplayName("Cada estado tiene el texto exacto que se muestra en la tabla")
    void labelsMatchDashboardBadges() {
        assertThat(ShipmentStatus.PENDING_FIRST_CHECK.getLabel()).isEqualTo("Pendiente");
        assertThat(ShipmentStatus.IN_TRANSIT.getLabel()).isEqualTo("En Tránsito");
        assertThat(ShipmentStatus.NEAR_ARRIVAL.getLabel()).isEqualTo("Arribo Próximo");
        assertThat(ShipmentStatus.ARRIVED.getLabel()).isEqualTo("Arribado");
        assertThat(ShipmentStatus.CANCELLED.getLabel()).isEqualTo("Cancelado");
        assertThat(ShipmentStatus.ERROR_CHECKING.getLabel()).isEqualTo("Error");
        assertThat(ShipmentStatus.MANUAL_CHECK.getLabel()).isEqualTo("Verificar manual");
    }

    @Test
    @DisplayName("Solo pendiente y error de consulta admiten borrado manual")
    void onlyPendingAndErrorCanBeDeletedManually() {
        assertThat(ShipmentStatus.PENDING_FIRST_CHECK.canBeDeletedManually())
                .as("PENDING_FIRST_CHECK todavía no se ha monitoreado")
                .isTrue();
        assertThat(ShipmentStatus.ERROR_CHECKING.canBeDeletedManually())
                .as("ERROR_CHECKING no tiene datos útiles que perder")
                .isTrue();

        assertThat(ShipmentStatus.IN_TRANSIT.canBeDeletedManually())
                .as("IN_TRANSIT ya tiene datos del viaje")
                .isFalse();
        assertThat(ShipmentStatus.NEAR_ARRIVAL.canBeDeletedManually())
                .as("NEAR_ARRIVAL está por arribar")
                .isFalse();
        assertThat(ShipmentStatus.ARRIVED.canBeDeletedManually())
                .as("ARRIVED ya confirmó el arribo")
                .isFalse();
        assertThat(ShipmentStatus.CANCELLED.canBeDeletedManually())
                .as("CANCELLED conserva el motivo de la cancelación")
                .isFalse();
        assertThat(ShipmentStatus.MANUAL_CHECK.canBeDeletedManually())
                .as("MANUAL_CHECK espera revisión de un operador")
                .isFalse();
    }

    @Test
    @DisplayName("El borrado manual cubre exactamente dos de los siete estados")
    void manualDeletionCoversExactlyTwoStatuses() {
        assertThat(Arrays.stream(ShipmentStatus.values())
                .filter(ShipmentStatus::canBeDeletedManually))
                .containsExactlyInAnyOrder(
                        ShipmentStatus.PENDING_FIRST_CHECK,
                        ShipmentStatus.ERROR_CHECKING);
    }

    @Test
    @DisplayName("Las constantes persistidas conservan su nombre exacto")
    void persistedNamesAreUnchanged() {
        assertThat(Arrays.stream(ShipmentStatus.values()).map(Enum::name))
                .containsExactly(
                        "PENDING_FIRST_CHECK",
                        "IN_TRANSIT",
                        "NEAR_ARRIVAL",
                        "ARRIVED",
                        "CANCELLED",
                        "ERROR_CHECKING",
                        "MANUAL_CHECK");
    }
}
