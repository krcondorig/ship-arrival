package com.shiparrival.adapter.msc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiparrival.adapter.TrackingResult;
import com.shiparrival.domain.ShipmentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Prueba manual contra el portal real de MSC.
 *
 * <p>Se omite en la suite normal. Para ejecutarla:</p>
 * <pre>
 * $env:MSC_BROWSER_TEST="true"
 * $env:MSC_ARRIVED_GUIDE="..."
 * $env:MSC_IN_TRANSIT_GUIDE="..."
 * .\mvnw.cmd test -Dtest=MscTrackingBrowserIntegrationTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "MSC_BROWSER_TEST", matches = "true")
class MscTrackingBrowserIntegrationTest {

    @Test
    @DisplayName("Debe rastrear guías reales de MSC mediante Chrome headless")
    void shouldTrackRealMscShipments() {
        String arrivedGuide = System.getenv("MSC_ARRIVED_GUIDE");
        String inTransitGuide = System.getenv("MSC_IN_TRANSIT_GUIDE");
        assumeTrue(arrivedGuide != null && !arrivedGuide.isBlank(),
                "MSC_ARRIVED_GUIDE no está definida");
        assumeTrue(inTransitGuide != null && !inTransitGuide.isBlank(),
                "MSC_IN_TRANSIT_GUIDE no está definida");

        SeleniumMscTrackingClient client = new SeleniumMscTrackingClient();
        MscTrackingAdapter adapter = new MscTrackingAdapter(client, new ObjectMapper());

        try {
            TrackingResult arrived = adapter.track(arrivedGuide);
            assertThat(arrived.isSuccess())
                    .as("rastreo de guía arribada: %s", arrived.getErrorMessage())
                    .isTrue();
            assertThat(arrived.getActualArrival()).isNotNull();
            assertThat(arrived.getDetectedStatus()).isEqualTo(ShipmentStatus.ARRIVED);
            assertThat(arrived.getVesselName()).isNotBlank();
            assertThat(arrived.getPortOfDischarge()).isNotBlank();

            TrackingResult inTransit = adapter.track(inTransitGuide);
            assertThat(inTransit.isSuccess())
                    .as("rastreo de guía en tránsito: %s", inTransit.getErrorMessage())
                    .isTrue();
            assertThat(inTransit.getActualArrival()).isNull();
            assertThat(inTransit.getEstimatedArrival()).isNotNull();
            assertThat(inTransit.getDetectedStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
            assertThat(inTransit.getVesselName()).isNotBlank();
            assertThat(inTransit.getPortOfDischarge()).isNotBlank();
        } finally {
            client.close();
        }
    }
}
