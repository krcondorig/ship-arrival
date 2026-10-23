package com.shiparrival.service;

import com.shiparrival.domain.Carrier;
import com.shiparrival.domain.Shipment;
import com.shiparrival.domain.ShipmentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DiscordNotificationServiceTest {

    @Test
    @DisplayName("No debe enviar mensaje si discord.bot.enabled está en false")
    void shouldNotSendMessageWhenDisabled() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        DiscordNotificationService service = new DiscordNotificationService(
                restClientBuilder, "https://discord.com/api/webhooks/test", false
        );

        Shipment shipment = Shipment.builder()
                .trackingNumber("TESTBL900001")
                .carrier(Carrier.ONE)
                .build();

        boolean sent = service.sendNearArrivalAlert(shipment, 2);
        assertThat(sent).isFalse();
    }

    @Test
    @DisplayName("Debe enviar alerta de proximidad a Discord cuando está habilitado")
    void shouldSendNearArrivalAlertWhenEnabled() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        String webhookUrl = "https://discord.com/api/webhooks/123/abc";
        DiscordNotificationService service = new DiscordNotificationService(
                restClientBuilder, webhookUrl, true
        );

        mockServer.expect(requestTo(webhookUrl))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("TESTBL900001")))
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("ONE FANTASTIC")))
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("MANZANILLO, MEXICO")))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        Shipment shipment = Shipment.builder()
                .trackingNumber("TESTBL900001")
                .carrier(Carrier.ONE)
                .vesselName("ONE FANTASTIC")
                .voyageNumber("006E")
                .portOfDischarge("MANZANILLO, MEXICO")
                .estimatedArrival(LocalDateTime.of(2026, 10, 20, 9, 0))
                .status(ShipmentStatus.NEAR_ARRIVAL)
                .build();

        boolean sent = service.sendNearArrivalAlert(shipment, 2);
        assertThat(sent).isTrue();
        mockServer.verify();
    }

    @Test
    @DisplayName("Debe capturar errores del Webhook de Discord y retornar false sin lanzar excepción")
    void shouldHandleDiscordApiFailureGracefully() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        String webhookUrl = "https://discord.com/api/webhooks/123/abc";
        DiscordNotificationService service = new DiscordNotificationService(
                restClientBuilder, webhookUrl, true
        );

        mockServer.expect(requestTo(webhookUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        Shipment shipment = Shipment.builder()
                .trackingNumber("TESTBL900001")
                .carrier(Carrier.ONE)
                .build();

        boolean sent = service.sendNearArrivalAlert(shipment, 1);
        assertThat(sent).isFalse();
    }
}
