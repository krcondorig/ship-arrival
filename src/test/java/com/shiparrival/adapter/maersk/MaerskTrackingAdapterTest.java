package com.shiparrival.adapter.maersk;

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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MaerskTrackingAdapterTest {

    private MaerskTrackingClient client;
    private MaerskTrackingAdapter adapter;

    @BeforeEach
    void setUp() {
        client = mock(MaerskTrackingClient.class);
        adapter = new MaerskTrackingAdapter(client, new ObjectMapper());
    }

    @Test
    @DisplayName("Debe soportar únicamente la naviera MAERSK")
    void shouldSupportOnlyMaersk() {
        assertThat(adapter.supports(Carrier.MAERSK)).isTrue();
        assertThat(adapter.supports(Carrier.ONE)).isFalse();
        assertThat(adapter.supports(Carrier.MSC)).isFalse();

        CarrierTrackingAdapterFactory factory = new CarrierTrackingAdapterFactory(List.of(adapter));
        CarrierTrackingAdapter retrieved = factory.getAdapter(Carrier.MAERSK);
        assertThat(retrieved).isSameAs(adapter);
    }

    @Test
    @DisplayName("Debe mapear una guía Maersk ya arribada a Manzanillo")
    void shouldMapArrivedShipment() throws IOException {
        when(client.fetchTrackingJson("TESTMAE0001")).thenReturn(fixture("maersk-arrived.json"));

        TrackingResult result = adapter.track("TESTMAE0001");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTrackingNumber()).isEqualTo("TESTMAE0001");
        assertThat(result.getContainerNumber()).isEqualTo("TESTU1234567");
        assertThat(result.getVesselName()).isEqualTo("MAERSK EL ALTO");
        assertThat(result.getVoyageNumber()).isEqualTo("636E");
        assertThat(result.getPortOfLoading()).isEqualTo("QINGDAO, CN");
        assertThat(result.getPortOfDischarge()).isEqualTo("MANZANILLO, MX");
        assertThat(result.getDepartureDate()).isEqualTo(LocalDateTime.of(2026, 9, 5, 8, 15));
        assertThat(result.getActualArrival()).isEqualTo(LocalDateTime.of(2026, 10, 6, 12, 28));
        assertThat(result.getEstimatedArrival()).isEqualTo(LocalDateTime.of(2026, 10, 6, 12, 28));
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.ARRIVED);
        assertThat(result.getRawStatusMessage())
                .contains("ON-RAIL", "MANZANILLO");
    }

    @Test
    @DisplayName("Debe mapear una guía Maersk en tránsito usando el ETA del puerto final")
    void shouldMapInTransitShipment() throws IOException {
        when(client.fetchTrackingJson("TESTMAE0002")).thenReturn(fixture("maersk-intransit.json"));

        TrackingResult result = adapter.track("TESTMAE0002");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTrackingNumber()).isEqualTo("TESTMAE0002");
        assertThat(result.getContainerNumber()).isEqualTo("TESTU7654321");
        assertThat(result.getVesselName()).isEqualTo("MAERSK EDINBURGH");
        assertThat(result.getVoyageNumber()).isEqualTo("638E");
        assertThat(result.getPortOfLoading()).isEqualTo("QINGDAO, CN");
        assertThat(result.getPortOfDischarge()).isEqualTo("MANZANILLO, MX");
        assertThat(result.getDepartureDate()).isEqualTo(LocalDateTime.of(2026, 9, 19, 23, 25));
        assertThat(result.getEstimatedArrival()).isEqualTo(LocalDateTime.of(2026, 10, 23, 5, 0));
        assertThat(result.getActualArrival()).isNull();
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(result.getRawStatusMessage())
                .contains("CONTAINER ARRIVAL", "MANZANILLO");
    }

    @Test
    @DisplayName("Debe devolver fallo cuando Maersk no devuelve contenedores")
    void shouldReturnFailureWhenNoContainers() {
        when(client.fetchTrackingJson("TESTMAE0003"))
                .thenReturn("{\"tpdoc_num\":\"TESTMAE0003\",\"containers\":[]}");

        TrackingResult result = adapter.track("TESTMAE0003");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.ERROR_CHECKING);
        assertThat(result.getErrorMessage()).contains("contenedores");
    }

    @Test
    @DisplayName("Debe devolver fallo cuando Maersk reporta el embarque cancelado")
    void shouldReturnFailureWhenShipmentIsCancelled() {
        when(client.fetchTrackingJson("TESTMAE0004"))
                .thenReturn("{\"tpdoc_num\":\"TESTMAE0004\",\"is_cancelled_shipment\":true,"
                        + "\"containers\":[{\"container_num\":\"TESTU0000004\"}]}");

        TrackingResult result = adapter.track("TESTMAE0004");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.ERROR_CHECKING);
        assertThat(result.getErrorMessage()).contains("cancelado");
    }

    @Test
    @DisplayName("Debe devolver fallo cuando Akamai responde con una página de bloqueo")
    void shouldReturnFailureWhenMaerskReturnsBlockedHtml() {
        when(client.fetchTrackingJson("TESTMAE0005"))
                .thenReturn("<html><title>Access Denied</title></html>");

        TrackingResult result = adapter.track("TESTMAE0005");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.ERROR_CHECKING);
        assertThat(result.getErrorMessage()).contains("bloqueo");
    }

    @Test
    @DisplayName("Debe devolver fallo cuando el cliente de navegador lanza una excepción")
    void shouldReturnFailureWhenClientThrows() {
        when(client.fetchTrackingJson("TESTMAE0006"))
                .thenThrow(new MaerskTrackingException("No se pudo iniciar el navegador"));

        TrackingResult result = adapter.track("TESTMAE0006");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.ERROR_CHECKING);
        assertThat(result.getErrorMessage()).contains("No se pudo iniciar el navegador");
    }

    @Test
    @DisplayName("Debe rechazar una guía vacía sin consultar el navegador")
    void shouldRejectEmptyTrackingNumber() {
        TrackingResult result = adapter.track("   ");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.ERROR_CHECKING);
        verify(client, never()).fetchTrackingJson(anyString());
    }

    @Test
    @DisplayName("Debe liberar los recursos del cliente al finalizar el ciclo")
    void shouldReleaseClientResources() {
        adapter.releaseResources();

        verify(client).close();
    }

    private String fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/maersk/" + name)) {
            assertThat(in).as("fixture %s", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
