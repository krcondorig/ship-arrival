package com.shiparrival.adapter.hapaglloyd;

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
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HapagLloydTrackingAdapterTest {

    private HapagLloydTrackingClient client;
    private HapagLloydTrackingAdapter adapter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        client = mock(HapagLloydTrackingClient.class);
        adapter = new HapagLloydTrackingAdapter(client);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("Debe soportar únicamente la naviera HAPAG_LLOYD")
    void shouldSupportOnlyHapagLloyd() {
        assertThat(adapter.supports(Carrier.HAPAG_LLOYD)).isTrue();
        assertThat(adapter.supports(Carrier.ONE)).isFalse();
        assertThat(adapter.supports(Carrier.MSC)).isFalse();
        assertThat(adapter.supports(Carrier.MAERSK)).isFalse();

        CarrierTrackingAdapterFactory factory = new CarrierTrackingAdapterFactory(List.of(adapter));
        CarrierTrackingAdapter retrieved = factory.getAdapter(Carrier.HAPAG_LLOYD);
        assertThat(retrieved).isSameAs(adapter);
    }

    @Test
    @DisplayName("Debe mapear una guía Hapag-Lloyd ya arribada a Manzanillo")
    void shouldMapArrivedShipment() throws IOException {
        when(client.fetchTrackingSnapshot("HLCUNG12607EKZE0"))
                .thenReturn(fixture("hapag-lloyd/arrived.json", HapagLloydTrackingSnapshot.class));

        TrackingResult result = adapter.track("HLCUNG12607EKZE0");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTrackingNumber()).isEqualTo("HLCUNG12607EKZE0");
        assertThat(result.getContainerNumber()).isEqualTo("HLBU2959982");
        assertThat(result.getVesselName()).isEqualTo("IQUIQUE EXPRESS");
        assertThat(result.getVoyageNumber()).isEqualTo("2636E");
        assertThat(result.getPortOfLoading()).isEqualTo("QINGDAO");
        assertThat(result.getPortOfDischarge()).isEqualTo("MANZANILLO, COL (MX)");
        assertThat(result.getDepartureDate()).isEqualTo(LocalDateTime.of(2026, 9, 6, 23, 54));
        assertThat(result.getActualArrival()).isEqualTo(LocalDateTime.of(2026, 10, 21, 15, 0));
        assertThat(result.getEstimatedArrival()).isEqualTo(LocalDateTime.of(2026, 10, 21, 15, 0));
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.ARRIVED);
        assertThat(result.getRawStatusMessage())
                .contains("Discharged", "MANZANILLO", "IQUIQUE EXPRESS", "2636E");
    }

    @Test
    @DisplayName("Debe mapear una guía Hapag-Lloyd en tránsito usando el ETA planificado del puerto final")
    void shouldMapInTransitShipment() throws IOException {
        when(client.fetchTrackingSnapshot("HLCUNG12608BMJY0"))
                .thenReturn(fixture("hapag-lloyd/intransit.json", HapagLloydTrackingSnapshot.class));

        TrackingResult result = adapter.track("HLCUNG12608BMJY0");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTrackingNumber()).isEqualTo("HLCUNG12608BMJY0");
        assertThat(result.getContainerNumber()).isEqualTo("TEMU1610106");
        assertThat(result.getVesselName()).isEqualTo("IQUIQUE EXPRESS");
        assertThat(result.getVoyageNumber()).isEqualTo("2640E");
        assertThat(result.getPortOfLoading()).isEqualTo("QINGDAO");
        assertThat(result.getPortOfDischarge()).isEqualTo("MANZANILLO, COL (MX)");
        assertThat(result.getDepartureDate()).isEqualTo(LocalDateTime.of(2026, 9, 25, 18, 0));
        assertThat(result.getActualArrival()).isNull();
        assertThat(result.getEstimatedArrival()).isEqualTo(LocalDateTime.of(2026, 10, 26, 8, 0));
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(result.getRawStatusMessage())
                .contains("Vessel departed", "BUSAN", "IQUIQUE EXPRESS", "2640E");
    }

    @Test
    @DisplayName("Debe devolver fallo cuando Hapag-Lloyd no devuelve datos")
    void shouldReturnFailureWhenSnapshotIsNull() {
        when(client.fetchTrackingSnapshot("HLCUNG0000000000")).thenReturn(null);

        TrackingResult result = adapter.track("HLCUNG0000000000");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.ERROR_CHECKING);
        assertThat(result.getErrorMessage()).contains("no devolvió datos");
    }

    @Test
    @DisplayName("Debe devolver fallo cuando Hapag-Lloyd no devuelve contenedores")
    void shouldReturnFailureWhenNoContainers() {
        HapagLloydTrackingSnapshot snapshot = HapagLloydTrackingSnapshot.builder()
                .trackingNumber("HLCUNG0000000001")
                .containers(List.of())
                .build();
        when(client.fetchTrackingSnapshot("HLCUNG0000000001")).thenReturn(snapshot);

        TrackingResult result = adapter.track("HLCUNG0000000001");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.ERROR_CHECKING);
        assertThat(result.getErrorMessage()).contains("contenedores");
    }

    @Test
    @DisplayName("Debe devolver fallo cuando el contenedor no tiene movimientos")
    void shouldReturnFailureWhenNoMovements() {
        HapagLloydContainer container = HapagLloydContainer.builder()
                .containerNumber("HLBU 1234567")
                .containerType("22GP")
                .build();
        HapagLloydTrackingSnapshot snapshot = HapagLloydTrackingSnapshot.builder()
                .trackingNumber("HLCUNG0000000002")
                .containers(List.of(container))
                .build();
        when(client.fetchTrackingSnapshot("HLCUNG0000000002")).thenReturn(snapshot);

        TrackingResult result = adapter.track("HLCUNG0000000002");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.ERROR_CHECKING);
        assertThat(result.getErrorMessage()).contains("movimientos");
    }

    @Test
    @DisplayName("Debe devolver fallo cuando el cliente de navegador lanza una excepción")
    void shouldReturnFailureWhenClientThrows() {
        when(client.fetchTrackingSnapshot("HLCUNG0000000003"))
                .thenThrow(new HapagLloydTrackingException("Cloudflare bloqueó la consulta"));

        TrackingResult result = adapter.track("HLCUNG0000000003");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.ERROR_CHECKING);
        assertThat(result.getErrorMessage()).contains("Cloudflare bloqueó la consulta");
    }

    @Test
    @DisplayName("Debe rechazar una guía vacía sin consultar el navegador")
    void shouldRejectEmptyTrackingNumber() {
        TrackingResult result = adapter.track("   ");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.ERROR_CHECKING);
        verify(client, never()).fetchTrackingSnapshot(anyString());
    }

    @Test
    @DisplayName("Debe liberar los recursos del cliente al finalizar el ciclo")
    void shouldReleaseClientResources() {
        adapter.releaseResources();

        verify(client).close();
    }

    private <T> T fixture(String name, Class<T> type) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("fixtures/" + name)) {
            assertThat(in).as("fixture %s", name).isNotNull();
            return objectMapper.readValue(in, type);
        }
    }
}
