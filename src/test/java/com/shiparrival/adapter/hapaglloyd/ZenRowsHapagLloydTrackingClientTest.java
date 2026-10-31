package com.shiparrival.adapter.hapaglloyd;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;

class ZenRowsHapagLloydTrackingClientTest {

    @Test
    void propertiesExternalizeZenRowsSecretWithoutHardCodingIt() throws IOException {
        String properties = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/application.properties"));
        assertThat(properties)
                .contains("hapag-lloyd.zenrows.api-key=${ZENROWS_API_KEY:}")
                .contains("hapag-lloyd.zenrows.timeout-seconds=${ZENROWS_TIMEOUT_SECONDS:180}");
    }

    private static final String API_KEY = "test-key";
    private static final String BASE_URL = "http://localhost:9876/v1/";

    @Test
    void fetchesAndMapsOnlyFirstContainerFromBooking() throws IOException {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL)))
                .andExpect(method(GET))
                .andExpect(queryParam("apikey", API_KEY))
                .andExpect(queryParam("mode", "auto"))
                .andRespond(withSuccess(fixture("zenrows-booking.html"), org.springframework.http.MediaType.TEXT_HTML));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL)))
                .andExpect(method(GET))
                .andExpect(queryParam("url", org.hamcrest.Matchers.containsString("container%3DHLBU%252B%252B2959982")))
                .andRespond(withSuccess(fixture("zenrows-container.html"), org.springframework.http.MediaType.TEXT_HTML));

        ZenRowsHapagLloydTrackingClient client = new ZenRowsHapagLloydTrackingClient(
                builder, API_KEY, BASE_URL, 1000);
        HapagLloydTrackingSnapshot snapshot = client.fetchTrackingSnapshot("TESTBL00000001");

        assertThat(snapshot.getContainers()).hasSize(1);
        assertThat(snapshot.getContainers().get(0).getContainerNumber()).isEqualTo("HLBU 2959982");
        assertThat(snapshot.getContainers().get(0).getContainerType()).isEqualTo("22GP");
        assertThat(snapshot.getContainers().get(0).getMovements()).hasSize(2);
        assertThat(snapshot.getContainers().get(0).getMovements().get(0).isActual()).isTrue();
        server.verify();
    }

    @Test
    void mapsApiKeyFromSpringConfigurationProperty() {
        RestClient.Builder builder = RestClient.builder();
        ZenRowsHapagLloydTrackingClient client = new ZenRowsHapagLloydTrackingClient(
                builder, "configured-key", BASE_URL, 1000);

        assertThat(ReflectionTestUtils.getField(client, "apiKey")).isEqualTo("configured-key");
    }

    @Test
    void rejectsMissingApiKey() {
        ZenRowsHapagLloydTrackingClient client = new ZenRowsHapagLloydTrackingClient(
                RestClient.builder(), " ", BASE_URL, 1000);

        assertThatThrownBy(() -> client.fetchTrackingSnapshot("TESTBL00000001"))
                .isInstanceOf(HapagLloydTrackingException.class)
                .hasMessageContaining("ZENROWS_API_KEY");
    }

    @Test
    void rejectsNonSuccessfulZenRowsResponseWithoutLeakingResponseBody() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL)))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("sensitive response body"));
        ZenRowsHapagLloydTrackingClient client = new ZenRowsHapagLloydTrackingClient(
                builder, API_KEY, BASE_URL, 1000);

        assertThatThrownBy(() -> client.fetchTrackingSnapshot("TESTBL00000001"))
                .isInstanceOf(HapagLloydTrackingException.class)
                .hasMessageNotContaining("sensitive response body")
                .hasMessageNotContaining(API_KEY);
        server.verify();
    }

    @Test
    void reportsSafeDetailsPageMetadataWhenNoMovementsAreFound() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL)))
                .andRespond(withSuccess(fixtureUnchecked("zenrows-booking.html"), org.springframework.http.MediaType.TEXT_HTML));
        String oversizedClassName = "c".repeat(100);
        String unexpectedPage = "<html><head><title>Unexpected page</title></head><body>PRIVATE BODY</body>"
                + ("<table class=\"" + oversizedClassName + "\"></table>").repeat(10) + "</html>";
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL)))
                .andRespond(withSuccess(unexpectedPage, org.springframework.http.MediaType.TEXT_HTML));
        ZenRowsHapagLloydTrackingClient client = new ZenRowsHapagLloydTrackingClient(
                builder, API_KEY, BASE_URL, 1000);

        assertThatThrownBy(() -> client.fetchTrackingSnapshot("TESTBL00000001"))
                .isInstanceOf(HapagLloydTrackingException.class)
                .hasMessageContaining("Unexpected page")
                .hasMessageNotContaining("PRIVATE BODY")
                .hasMessageNotContaining(API_KEY)
                .hasMessageContaining("httpStatus=200")
                .hasMessageContaining("tableCount=10")
                .satisfies(ex -> assertThat(ex.getMessage()).hasSizeLessThanOrEqualTo(512));
        server.verify();
    }

    @Test
    void parsesMovementsFromSharedHapagLloydDetailsPageStructure() throws IOException {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL)))
                .andRespond(withSuccess(fixture("zenrows-booking.html"), org.springframework.http.MediaType.TEXT_HTML));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL)))
                .andRespond(withSuccess(fixture("zenrows-details-shared-structure.html"),
                        org.springframework.http.MediaType.TEXT_HTML));
        ZenRowsHapagLloydTrackingClient client = new ZenRowsHapagLloydTrackingClient(
                builder, API_KEY, BASE_URL, 1000);

        HapagLloydTrackingSnapshot snapshot = client.fetchTrackingSnapshot("TESTBL00000001");

        assertThat(snapshot.getContainers()).hasSize(1);
        assertThat(snapshot.getContainers().get(0).getMovements()).hasSize(2);
        assertThat(snapshot.getContainers().get(0).getMovements().get(0).getStatus()).isEqualTo("Vessel departed");
        assertThat(snapshot.getContainers().get(0).getMovements().get(1).getPlace())
                .isEqualTo("MANZANILLO, COL (MX)");
        server.verify();
    }

    @Test
    void rejectsDetailsPageWithoutMovements() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL)))
                .andRespond(withSuccess(fixtureUnchecked("zenrows-booking.html"), org.springframework.http.MediaType.TEXT_HTML));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL)))
                .andRespond(withSuccess("<html><table class=groupBorder></table></html>", org.springframework.http.MediaType.TEXT_HTML));
        ZenRowsHapagLloydTrackingClient client = new ZenRowsHapagLloydTrackingClient(
                builder, API_KEY, BASE_URL, 1000);

        assertThatThrownBy(() -> client.fetchTrackingSnapshot("TESTBL00000001"))
                .isInstanceOf(HapagLloydTrackingException.class)
                .hasMessageContaining("movimientos");
        server.verify();
    }

    @Test
    void rejectsBookingPageWithoutContainers() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL)))
                .andRespond(withSuccess("<html><body>no results</body></html>", org.springframework.http.MediaType.TEXT_HTML));
        ZenRowsHapagLloydTrackingClient client = new ZenRowsHapagLloydTrackingClient(
                builder, API_KEY, BASE_URL, 1000);

        assertThatThrownBy(() -> client.fetchTrackingSnapshot("TESTBL00000001"))
                .isInstanceOf(HapagLloydTrackingException.class)
                .hasMessageContaining("contenedores");
        server.verify();
    }

    private String fixtureUnchecked(String name) {
        try {
            return fixture(name);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String fixture(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/fixtures/hapag-lloyd/" + name)) {
            if (input == null) throw new IOException("Fixture not found: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
