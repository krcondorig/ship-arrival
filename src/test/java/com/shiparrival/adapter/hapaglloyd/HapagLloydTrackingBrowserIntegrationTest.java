package com.shiparrival.adapter.hapaglloyd;

import com.shiparrival.adapter.TrackingResult;
import com.shiparrival.domain.ShipmentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Prueba manual contra el portal real de Hapag-Lloyd.
 *
 * <p>Se omite en la suite normal porque abre Chrome fuera de pantalla y tarda
 * varios segundos. Para ejecutarla:</p>
 * <pre>
 * $env:HAPAG_LLOYD_BROWSER_TEST="true"
 * $env:HAPAG_LLOYD_ARRIVED_GUIDE="HLCU..."
 * .\mvnw.cmd test -Dtest=HapagLloydTrackingBrowserIntegrationTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "HAPAG_LLOYD_BROWSER_TEST", matches = "true")
class HapagLloydTrackingBrowserIntegrationTest {

    @Test
    @DisplayName("Debe rastrear una guía real de Hapag-Lloyd mediante Chrome fuera de pantalla")
    void shouldTrackRealHapagLloydShipment() {
        String guide = System.getenv("HAPAG_LLOYD_ARRIVED_GUIDE");
        assumeTrue(guide != null && !guide.isBlank(),
                "HAPAG_LLOYD_ARRIVED_GUIDE no está definida");

        ChromeHapagLloydTrackingClient client = new ChromeHapagLloydTrackingClient();
        HapagLloydTrackingAdapter adapter = new HapagLloydTrackingAdapter(client);

        try {
            TrackingResult result = adapter.track(guide);

            assertThat(result.isSuccess())
                    .as("rastreo de guía Hapag-Lloyd: %s", result.getErrorMessage())
                    .isTrue();
            assertThat(result.getContainerNumber()).isNotBlank();
            assertThat(result.getVesselName()).isNotBlank();
            assertThat(result.getVoyageNumber()).isNotBlank();
            assertThat(result.getPortOfDischarge()).contains("MANZANILLO");
            assertThat(result.getActualArrival()).isNotNull();
            assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.ARRIVED);
        } finally {
            client.close();
        }
    }
}
