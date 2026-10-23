package com.shiparrival.adapter.maersk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiparrival.adapter.TrackingResult;
import com.shiparrival.domain.ShipmentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Prueba manual contra el portal real de Maersk.
 *
 * <p>Se omite en la suite normal. Para ejecutarla:</p>
 * <pre>
 * $env:MAERSK_BROWSER_TEST="true"
 * $env:MAERSK_ARRIVED_GUIDE="..."
 * $env:MAERSK_IN_TRANSIT_GUIDE="..."
 * .\mvnw.cmd test -Dtest=MaerskTrackingBrowserIntegrationTest
 * </pre>
 *
 * <p>La prueba tarda alrededor de 20-30 segundos porque Maersk/Akamai exige
 * un navegador nuevo para cada guía.</p>
 */
@EnabledIfEnvironmentVariable(named = "MAERSK_BROWSER_TEST", matches = "true")
class MaerskTrackingBrowserIntegrationTest {

    @Test
    @DisplayName("Debe rastrear guías reales de Maersk mediante Chrome headless")
    void shouldTrackRealMaerskShipments() {
        String arrivedGuide = System.getenv("MAERSK_ARRIVED_GUIDE");
        String inTransitGuide = System.getenv("MAERSK_IN_TRANSIT_GUIDE");
        assumeTrue(arrivedGuide != null && !arrivedGuide.isBlank(),
                "MAERSK_ARRIVED_GUIDE no está definida");
        assumeTrue(inTransitGuide != null && !inTransitGuide.isBlank(),
                "MAERSK_IN_TRANSIT_GUIDE no está definida");

        SeleniumMaerskTrackingClient client = new SeleniumMaerskTrackingClient();
        MaerskTrackingAdapter adapter = new MaerskTrackingAdapter(client, new ObjectMapper());

        try {
            TrackingResult arrived = adapter.track(arrivedGuide);
            assertThat(arrived.isSuccess())
                    .as("rastreo de guía arribada: %s", arrived.getErrorMessage())
                    .isTrue();
            assertThat(arrived.getActualArrival()).isNotNull();
            assertThat(arrived.getDetectedStatus()).isEqualTo(ShipmentStatus.ARRIVED);
            assertThat(arrived.getVesselName()).isNotBlank();
            assertThat(arrived.getPortOfDischarge()).contains("MANZANILLO");

            TrackingResult inTransit = adapter.track(inTransitGuide);
            assertThat(inTransit.isSuccess())
                    .as("rastreo de guía en tránsito: %s", inTransit.getErrorMessage())
                    .isTrue();
            assertThat(inTransit.getActualArrival()).isNull();
            assertThat(inTransit.getEstimatedArrival()).isNotNull();
            assertThat(inTransit.getDetectedStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
            assertThat(inTransit.getVesselName()).isNotBlank();
            assertThat(inTransit.getPortOfDischarge()).contains("MANZANILLO");
        } finally {
            client.close();
        }
    }
}
