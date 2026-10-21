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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OneLineTrackingAdapterTest {

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private OneLineTrackingAdapter adapter;
    private CarrierTrackingAdapterFactory factory;

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder();
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
    @DisplayName("Debe parsear exitosamente la respuesta de rastreo de ONE con guía válida")
    void shouldTrackValidOneShipment() {
        String jsonResponse = """
            {
              "data": {
                "blNo": "TAOGR4856400",
                "vesselName": "ONE FANTASTIC",
                "voyage": "006E",
                "pol": "SHANGHAI, CHINA",
                "pod": "MANZANILLO, MEXICO",
                "departureDate": "2026-09-20 13:06",
                "estimatedArrival": "2026-10-20 09:00",
                "status": "Vessel Departure from Port of Loading",
                "containers": [
                  {
                    "containerNo": "TCNU6631328",
                    "status": "In Transit"
                  }
                ]
              }
            }
            """;

        mockServer.expect(requestTo("https://ecomm.one-line.com/api/cargo-tracking?trackingNo=TAOGR4856400"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        TrackingResult result = adapter.track("TAOGR4856400");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTrackingNumber()).isEqualTo("TAOGR4856400");
        assertThat(result.getVesselName()).isEqualTo("ONE FANTASTIC");
        assertThat(result.getVoyageNumber()).isEqualTo("006E");
        assertThat(result.getPortOfLoading()).isEqualTo("SHANGHAI, CHINA");
        assertThat(result.getPortOfDischarge()).isEqualTo("MANZANILLO, MEXICO");
        assertThat(result.getDepartureDate()).isEqualTo(LocalDateTime.of(2026, 9, 20, 13, 6));
        assertThat(result.getEstimatedArrival()).isEqualTo(LocalDateTime.of(2026, 10, 20, 9, 0));
        assertThat(result.getContainerNumber()).isEqualTo("TCNU6631328");
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
    }

    @Test
    @DisplayName("Debe manejar errores de red o HTTP 500 retornando resultado fallido sin lanzar excepción")
    void shouldHandleHttpErrorGracefully() {
        mockServer.expect(requestTo("https://ecomm.one-line.com/api/cargo-tracking?trackingNo=ERROR123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        TrackingResult result = adapter.track("ERROR123");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getTrackingNumber()).isEqualTo("ERROR123");
        assertThat(result.getDetectedStatus()).isEqualTo(ShipmentStatus.ERROR_CHECKING);
        assertThat(result.getErrorMessage()).contains("500");
    }
}
