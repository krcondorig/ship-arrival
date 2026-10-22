package com.shiparrival.adapter.one;

import com.shiparrival.adapter.CarrierTrackingAdapter;
import com.shiparrival.adapter.CarrierTrackingAdapterFactory;
import com.shiparrival.adapter.TrackingResult;
import com.shiparrival.domain.Carrier;
import com.shiparrival.domain.ShipmentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OneLineTrackingAdapterTest {

    private static final String ENDPOINT = "https://ecomm.one-line.com/api/v2/edh/containers/track-and-trace/search";

    private MockRestServiceServer mockServer;
    private OneLineTrackingAdapter adapter;
    private CarrierTrackingAdapterFactory factory;

    @BeforeEach
    void setUp() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        adapter = new OneLineTrackingAdapter(restClientBuilder);
        factory = new CarrierTrackingAdapterFactory(List.of(adapter));
    }

    @Test
    @DisplayName("Debe verificar que la factoría devuelva el adaptador adecuado para ONE")
    void shouldSupportCarrierOne() {
        assertThat(adapter.supports(Carrier.ONE)).isTrue();
        assertThat(adapter.supports(Carrier.MSC)).isFalse();

        CarrierTrackingAdapter retrieved = factory.getAdapter(Carrier.ONE);
        assertThat(retrieved).isSameAs(adapter);
    }

    @Test
    @DisplayName("Debe mapear una guía ya arribada usando una respuesta real anonimizada de ONE")
    void shouldMapArrivedShipmentFromAnonymizedOneResponse() throws IOException {
        mockServer.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(fixture("TESTBL000001.json"), MediaType.APPLICATION_JSON));

        TrackingResult result = adapter.track("TESTBL000001");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTrackingNumber()).isEqualTo("TESTBL000001");
        assertThat(result.getContainerNumber()).isEqualTo("TEST1234501");
        assertThat(result.getVesselName()).isEqualTo("IQUIQUE EXPRESS");
        assertThat(result.getVoyageNumber()).isEqualTo("2636");
        assertThat(result.getPortOfLoading()).isEqualTo("QINGDAO, SHANDONG");
        assertThat(result.getPortOfDischarge()).isEqualTo("MANZANILLO");
        assertThat(result.getDepartureDate()).isEqualTo(LocalDateTime.of(2026, 9, 20, 13, 6));
        assertThat(result.getEstimatedArrival()).isEqualTo(LocalDateTime.of(2026, 10, 21, 14, 36));
        assertThat(result.getActualArrival()).isEqualTo(LocalDateTime.of(2026, 10, 21, 14, 36));
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.ARRIVED);
        assertThat(result.getRawStatusMessage())
                .contains("Vessel Arrival at Port of Discharge", "MANZANILLO");
    }

    @Test
    @DisplayName("Debe mapear una guía en tránsito usando el ETA estimado del evento de Manzanillo")
    void shouldMapInTransitShipmentWithEstimatedEtaFromAnonymizedOneResponse() throws IOException {
        mockServer.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(fixture("TESTBL000003.json"), MediaType.APPLICATION_JSON));

        TrackingResult result = adapter.track("TESTBL000003");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTrackingNumber()).isEqualTo("TESTBL000003");
        assertThat(result.getContainerNumber()).isEqualTo("TEST3234501");
        assertThat(result.getVesselName()).isEqualTo("MSC NOA ARIELA");
        assertThat(result.getVoyageNumber()).isEqualTo("0637");
        assertThat(result.getPortOfLoading()).isEqualTo("HONG KONG");
        assertThat(result.getPortOfDischarge()).isEqualTo("MANZANILLO");
        assertThat(result.getDepartureDate()).isEqualTo(LocalDateTime.of(2026, 9, 28, 8, 15));
        assertThat(result.getEstimatedArrival()).isEqualTo(LocalDateTime.of(2026, 10, 28, 4, 0));
        assertThat(result.getActualArrival()).isNull();
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(result.getRawStatusMessage())
                .contains("Vessel Departure from Port of Loading", "HONG KONG");
    }

    @Test
    @DisplayName("Debe mapear una guía descargada en Manzanillo usando su evento más reciente")
    void shouldMapUnloadedShipmentFromAnonymizedOneResponse() throws IOException {
        mockServer.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(fixture("TESTBL000002.json"), MediaType.APPLICATION_JSON));

        TrackingResult result = adapter.track("TESTBL000002");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTrackingNumber()).isEqualTo("TESTBL000002");
        assertThat(result.getContainerNumber()).isEqualTo("TEST2234501");
        assertThat(result.getVesselName()).isEqualTo("HMM OAKLAND");
        assertThat(result.getVoyageNumber()).isEqualTo("0140");
        assertThat(result.getPortOfLoading()).isEqualTo("QINGDAO, SHANDONG");
        assertThat(result.getPortOfDischarge()).isEqualTo("MANZANILLO");
        assertThat(result.getDepartureDate()).isEqualTo(LocalDateTime.of(2026, 9, 12, 3, 20));
        assertThat(result.getEstimatedArrival()).isEqualTo(LocalDateTime.of(2026, 10, 7, 2, 46));
        assertThat(result.getActualArrival()).isEqualTo(LocalDateTime.of(2026, 10, 7, 2, 46));
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.ARRIVED);
        assertThat(result.getRawStatusMessage()).contains("Unloaded from Vessel");
    }

    @Test
    @DisplayName("Debe devolver fallo cuando ONE no encuentra la guía")
    void shouldReturnFailureWhenOneReturnsNoResults() throws IOException {
        mockServer.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(fixture("INVALID123.json"), MediaType.APPLICATION_JSON));

        TrackingResult result = adapter.track("INVALID123");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.ERROR_CHECKING);
        assertThat(result.getErrorMessage()).contains("INVALID123");
    }

    @Test
    @DisplayName("Debe devolver fallo cuando ONE responde con error HTTP")
    void shouldReturnFailureOnHttpError() {
        mockServer.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        TrackingResult result = adapter.track("ERROR123");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.ERROR_CHECKING);
        assertThat(result.getErrorMessage()).contains("500");
    }

    private String fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/one/" + name)) {
            assertThat(in).as("fixture %s", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
