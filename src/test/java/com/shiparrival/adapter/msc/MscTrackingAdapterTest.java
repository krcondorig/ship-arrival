package com.shiparrival.adapter.msc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiparrival.adapter.CarrierTrackingAdapter;
import com.shiparrival.adapter.CarrierTrackingAdapterFactory;
import com.shiparrival.adapter.TrackingResult;
import com.shiparrival.domain.Carrier;
import com.shiparrival.domain.ShipmentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MscTrackingAdapterTest {

    private MscTrackingClient client;
    private MscTrackingAdapter adapter;

    @BeforeEach
    void setUp() {
        client = mock(MscTrackingClient.class);
        adapter = new MscTrackingAdapter(client, new ObjectMapper());
    }

    @Test
    @DisplayName("Debe soportar únicamente la naviera MSC")
    void shouldSupportOnlyMsc() {
        assertThat(adapter.supports(Carrier.MSC)).isTrue();
        assertThat(adapter.supports(Carrier.ONE)).isFalse();

        CarrierTrackingAdapterFactory factory = new CarrierTrackingAdapterFactory(List.of(adapter));
        CarrierTrackingAdapter retrieved = factory.getAdapter(Carrier.MSC);
        assertThat(retrieved).isSameAs(adapter);
    }

    @Test
    @DisplayName("Debe mapear una guía MSC ya arribada usando el evento de descarga en el puerto destino")
    void shouldMapArrivedShipment() throws IOException {
        when(client.fetchTrackingJson("TESTMSC0001")).thenReturn(fixture("msc-arrived.json"));

        TrackingResult result = adapter.track("TESTMSC0001");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTrackingNumber()).isEqualTo("TESTMSC0001");
        assertThat(result.getContainerNumber()).isEqualTo("TESTU1234567");
        assertThat(result.getVesselName()).isEqualTo("MSC LAUSANNE VI");
        assertThat(result.getVoyageNumber()).isEqualTo("QM639R");
        assertThat(result.getPortOfLoading()).isEqualTo("QINGDAO, CN");
        assertThat(result.getPortOfDischarge()).isEqualTo("MANZANILLO, MX");
        assertThat(result.getDepartureDate()).isEqualTo(LocalDateTime.of(2026, 9, 9, 0, 0));
        assertThat(result.getActualArrival()).isEqualTo(LocalDateTime.of(2026, 10, 14, 0, 0));
        assertThat(result.getEstimatedArrival()).isEqualTo(LocalDateTime.of(2026, 10, 14, 0, 0));
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.ARRIVED);
        assertThat(result.getRawStatusMessage())
                .contains("Import Loaded on Rail", "MANZANILLO");
    }

    @Test
    @DisplayName("Debe mapear una guía MSC en tránsito usando el ETA final publicado")
    void shouldMapInTransitShipment() throws IOException {
        when(client.fetchTrackingJson("TESTMSC0002")).thenReturn(fixture("msc-intransit.json"));

        TrackingResult result = adapter.track("TESTMSC0002");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTrackingNumber()).isEqualTo("TESTMSC0002");
        assertThat(result.getContainerNumber()).isEqualTo("TESTU7654321");
        assertThat(result.getVesselName()).isEqualTo("MSC VALENTINA");
        assertThat(result.getVoyageNumber()).isEqualTo("FA639A");
        assertThat(result.getPortOfLoading()).isEqualTo("SHEKOU, CN");
        assertThat(result.getPortOfDischarge()).isEqualTo("MANZANILLO, MX");
        assertThat(result.getDepartureDate()).isEqualTo(LocalDateTime.of(2026, 10, 7, 0, 0));
        assertThat(result.getEstimatedArrival()).isEqualTo(LocalDateTime.of(2026, 11, 7, 0, 0));
        assertThat(result.getActualArrival()).isNull();
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(result.getRawStatusMessage())
                .contains("Estimated Time of Arrival", "MANZANILLO");
    }

    @Test
    @DisplayName("Debe devolver fallo cuando MSC no encuentra la guía")
    void shouldReturnFailureWhenMscDoesNotFindShipment() throws IOException {
        when(client.fetchTrackingJson("INVALID")).thenReturn(fixture("msc-not-found.json"));

        TrackingResult result = adapter.track("INVALID");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.ERROR_CHECKING);
        assertThat(result.getErrorMessage()).contains("Container not found");
    }

    @Test
    @DisplayName("Debe devolver fallo cuando MSC responde sin Bill of Ladings")
    void shouldReturnFailureWhenNoBillOfLadings() throws IOException {
        when(client.fetchTrackingJson("TESTMSC0003")).thenReturn(fixture("msc-empty.json"));

        TrackingResult result = adapter.track("TESTMSC0003");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.ERROR_CHECKING);
        assertThat(result.getErrorMessage()).contains("TESTMSC0003");
    }

    @Test
    @DisplayName("Debe devolver fallo cuando Akamai responde con una página de bloqueo")
    void shouldReturnFailureWhenMscReturnsBlockedHtml() throws IOException {
        when(client.fetchTrackingJson("TESTMSC0004")).thenReturn(fixture("msc-blocked.html"));

        TrackingResult result = adapter.track("TESTMSC0004");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.ERROR_CHECKING);
        assertThat(result.getErrorMessage()).contains("bloqueo");
    }

    @Test
    @DisplayName("Debe devolver fallo cuando el cliente de navegador lanza una excepción")
    void shouldReturnFailureWhenClientThrows() {
        when(client.fetchTrackingJson("TESTMSC0005"))
                .thenThrow(new MscTrackingException("No se pudo iniciar el navegador"));

        TrackingResult result = adapter.track("TESTMSC0005");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.ERROR_CHECKING);
        assertThat(result.getErrorMessage()).contains("No se pudo iniciar el navegador");
    }

    @Test
    @DisplayName("Debe liberar el navegador al finalizar el ciclo de monitoreo")
    void shouldReleaseClientResources() {
        adapter.releaseResources();

        verify(client).close();
    }

    private String fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/msc/" + name)) {
            assertThat(in).as("fixture %s", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
