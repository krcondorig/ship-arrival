package com.shiparrival;

import com.shiparrival.adapter.TrackingResult;
import com.shiparrival.adapter.one.OneLineTrackingAdapter;
import com.shiparrival.domain.Carrier;
import com.shiparrival.domain.Shipment;
import com.shiparrival.domain.ShipmentEventLog;
import com.shiparrival.domain.ShipmentStatus;
import com.shiparrival.repository.ShipmentEventLogRepository;
import com.shiparrival.repository.ShipmentRepository;
import com.shiparrival.service.TelegramNotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ShipArrivalE2EIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private ShipmentEventLogRepository shipmentEventLogRepository;

    @MockitoBean
    private TelegramNotificationService telegramNotificationService;

    @MockitoBean
    private OneLineTrackingAdapter oneLineTrackingAdapter;

    @Test
    @DisplayName("Flujo E2E completo: Registro manual -> Monitoreo -> Actualización de datos -> Alerta Telegram -> Dashboard e Historial")
    void completeEndToEndFlow() throws Exception {
        LocalDateTime etaTwoDaysAhead = LocalDateTime.now().plusDays(2).withHour(9).withMinute(0).withSecond(0).withNano(0);

        when(telegramNotificationService.sendNearArrivalAlert(any(), anyLong())).thenReturn(true);
        when(oneLineTrackingAdapter.supports(Carrier.ONE)).thenReturn(true);
        when(oneLineTrackingAdapter.track("TAOGR4856400")).thenReturn(TrackingResult.builder()
                .success(true)
                .trackingNumber("TAOGR4856400")
                .vesselName("ONE FANTASTIC")
                .voyageNumber("006E")
                .portOfLoading("SHANGHAI, CHINA")
                .portOfDischarge("MANZANILLO, MEXICO")
                .departureDate(LocalDateTime.now().minusDays(10))
                .estimatedArrival(etaTwoDaysAhead)
                .detectedStatus(ShipmentStatus.NEAR_ARRIVAL)
                .build());

        // 1. Registrar embarque individual vía POST /shipments
        mockMvc.perform(post("/shipments")
                        .param("trackingNumber", "TAOGR4856400")
                        .param("carrier", "ONE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        // Verificar que está en base de datos con estatus PENDING_FIRST_CHECK
        Shipment initial = shipmentRepository.findByTrackingNumber("TAOGR4856400").orElseThrow();
        assertThat(initial.getStatus()).isEqualTo(ShipmentStatus.PENDING_FIRST_CHECK);
        assertThat(initial.getCarrier()).isEqualTo(Carrier.ONE);

        // 2. Disparar POST /shipments/track-now (ciclo de monitoreo)
        mockMvc.perform(post("/shipments/track-now"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        // Verificar que se actualizó el embarque en base de datos
        Shipment updated = shipmentRepository.findByTrackingNumber("TAOGR4856400").orElseThrow();
        assertThat(updated.getVesselName()).isEqualTo("ONE FANTASTIC");
        assertThat(updated.getStatus()).isEqualTo(ShipmentStatus.NEAR_ARRIVAL);
        assertThat(updated.isAlertSent3Days()).isTrue();

        // Verificar que se disparó la alerta de Telegram
        verify(telegramNotificationService).sendNearArrivalAlert(any(Shipment.class), anyLong());

        // Verificar que se registró el evento de actualización de ETA
        List<ShipmentEventLog> logs = shipmentEventLogRepository.findByShipmentIdOrderByRecordedAtDesc(updated.getId());
        assertThat(logs).isNotEmpty();
        assertThat(logs.get(0).getEventType()).isEqualTo("ETA_UPDATED");

        // 3. Verificar que GET / (Dashboard) muestre los datos del buque y el badge de alerta
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("TAOGR4856400")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ONE FANTASTIC")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("MANZANILLO, MEXICO")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Arribo Próximo")));

        // 4. Verificar que GET /shipments/{id}/history muestre la bitácora
        mockMvc.perform(get("/shipments/" + updated.getId() + "/history"))
                .andExpect(status().isOk())
                .andExpect(view().name("history"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("TAOGR4856400")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ETA_UPDATED")));
    }
}
