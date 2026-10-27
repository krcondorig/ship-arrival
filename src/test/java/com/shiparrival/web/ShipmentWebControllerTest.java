package com.shiparrival.web;

import com.shiparrival.domain.Carrier;
import com.shiparrival.domain.Shipment;
import com.shiparrival.domain.ShipmentEventLog;
import com.shiparrival.domain.ShipmentStatus;
import com.shiparrival.service.GitHubActionsClient;
import com.shiparrival.service.LiveTrackingStatusService;
import com.shiparrival.service.ShipmentService;
import com.shiparrival.service.TrackingSchedulerService;
import com.shiparrival.service.dto.BatchImportSummary;
import com.shiparrival.service.dto.ShipmentStats;
import com.shiparrival.service.dto.TrackingStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ShipmentWebController.class)
class ShipmentWebControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShipmentService shipmentService;

    @MockitoBean
    private TrackingSchedulerService trackingSchedulerService;

    @MockitoBean
    private GitHubActionsClient gitHubActionsClient;

    @MockitoBean
    private LiveTrackingStatusService liveTrackingStatusService;

    @Test
    @DisplayName("GET / debe renderizar la vista dashboard con la lista de embarques")
    void shouldRenderDashboard() throws Exception {
        Shipment shipment = Shipment.builder()
                .id(1L)
                .trackingNumber("TESTBL900001")
                .carrier(Carrier.ONE)
                .vesselName("ONE FANTASTIC")
                .status(ShipmentStatus.IN_TRANSIT)
                .estimatedArrival(LocalDateTime.now().plusDays(5))
                .build();

        Page<Shipment> page = new PageImpl<>(List.of(shipment), PageRequest.of(0, 10), 1);
        when(shipmentService.getVisibleShipments(any(), any(Pageable.class))).thenReturn(page);
        when(shipmentService.getVisibleShipmentStats(any())).thenReturn(new ShipmentStats(1, 1, 0, 0));
        when(shipmentService.calculateDaysRemaining(any(), any())).thenReturn(5L);

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"))
                .andExpect(model().attributeExists("shipments"))
                .andExpect(model().attributeExists("totalCount"))
                .andExpect(model().attributeExists("nearArrivalCount"))
                .andExpect(model().attribute("currentPage", 0))
                .andExpect(model().attribute("totalPages", 1))
                .andExpect(model().attribute("totalElements", 1L));
    }

    @Test
    @DisplayName("GET / debe aplicar paginación y exponer metadatos de página")
    void shouldPaginateDashboard() throws Exception {
        Shipment shipment = Shipment.builder()
                .id(11L)
                .trackingNumber("TESTBL900011")
                .carrier(Carrier.ONE)
                .status(ShipmentStatus.IN_TRANSIT)
                .estimatedArrival(LocalDateTime.now().plusDays(5))
                .build();

        Page<Shipment> page = new PageImpl<>(List.of(shipment), PageRequest.of(1, 10), 25);
        when(shipmentService.getVisibleShipments(any(), any(Pageable.class))).thenReturn(page);
        when(shipmentService.getVisibleShipmentStats(any())).thenReturn(new ShipmentStats(25, 20, 3, 2));
        when(shipmentService.calculateDaysRemaining(any(), any())).thenReturn(5L);

        mockMvc.perform(get("/").param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"))
                .andExpect(model().attribute("currentPage", 1))
                .andExpect(model().attribute("totalPages", 3))
                .andExpect(model().attribute("totalElements", 25L))
                .andExpect(model().attribute("pageSize", 10))
                .andExpect(model().attribute("startItem", 11))
                .andExpect(model().attribute("endItem", 11));
    }

    @Test
    @DisplayName("GET / debe redirigir a la última página válida si la página solicitada no existe")
    void shouldRedirectWhenPageOutOfRange() throws Exception {
        Page<Shipment> emptyPage = new PageImpl<>(List.of(), PageRequest.of(5, 10), 25);
        when(shipmentService.getVisibleShipments(any(), any(Pageable.class))).thenReturn(emptyPage);

        mockMvc.perform(get("/").param("page", "5"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/?page=2&size=10"));
    }

    @Test
    @DisplayName("POST /shipments/{id}/delete debe conservar página y búsqueda")
    void shouldDeleteAndPreservePagination() throws Exception {
        mockMvc.perform(post("/shipments/1/delete")
                        .param("page", "2")
                        .param("size", "20")
                        .param("search", "ONE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/?page=2&size=20&search=ONE"));

        verify(shipmentService).deleteShipment(1L);
    }

    @Test
    @DisplayName("POST /shipments debe registrar una guía individual y redirigir")
    void shouldRegisterShipmentAndRedirect() throws Exception {
        when(shipmentService.existsByTrackingNumber("TESTBL900001")).thenReturn(false);
        when(shipmentService.registerShipment("TESTBL900001", Carrier.ONE))
                .thenReturn(new Shipment());

        mockMvc.perform(post("/shipments")
                        .param("trackingNumber", "TESTBL900001")
                        .param("carrier", "ONE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attributeExists("successMessage"));
    }

    @Test
    @DisplayName("POST /shipments debe advertir cuando la guía ya estaba registrada")
    void shouldWarnWhenShipmentAlreadyExists() throws Exception {
        Shipment existing = Shipment.builder()
                .id(33L)
                .trackingNumber("TESTBL900001")
                .carrier(Carrier.ONE)
                .build();

        when(shipmentService.existsByTrackingNumber("TESTBL900001")).thenReturn(true);
        when(shipmentService.registerShipment("TESTBL900001", Carrier.ONE)).thenReturn(existing);

        mockMvc.perform(post("/shipments")
                        .param("trackingNumber", "TESTBL900001")
                        .param("carrier", "ONE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attribute("warningMessage",
                        "La guía TESTBL900001 ya estaba registrada."))
                .andExpect(flash().attributeCount(1));
    }

    @Test
    @DisplayName("POST /shipments/upload-excel debe procesar el archivo y redirigir con resumen")
    void shouldUploadExcelAndRedirect() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "guias.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "dummy content".getBytes()
        );

        BatchImportSummary summary = BatchImportSummary.builder()
                .totalProcessed(2)
                .addedCount(2)
                .duplicateCount(0)
                .build();

        when(shipmentService.importFromExcel(any(), eq(Carrier.ONE))).thenReturn(summary);

        mockMvc.perform(multipart("/shipments/upload-excel")
                        .file(file)
                        .param("carrier", "ONE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attributeExists("successMessage"));
    }

    @Test
    @DisplayName("POST /shipments/track-now debe iniciar el monitoreo en segundo plano y redirigir")
    void shouldTriggerTrackNowAndRedirect() throws Exception {
        when(trackingSchedulerService.startTrackingCycleAsync()).thenReturn(true);

        mockMvc.perform(post("/shipments/track-now"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attributeExists("infoMessage"));

        verify(trackingSchedulerService).startTrackingCycleAsync();
    }

    @Test
    @DisplayName("POST /shipments/track-now debe advertir si ya hay un monitoreo en progreso")
    void shouldWarnWhenTrackingAlreadyInProgress() throws Exception {
        when(trackingSchedulerService.startTrackingCycleAsync()).thenReturn(false);

        mockMvc.perform(post("/shipments/track-now"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attributeExists("warningMessage"));
    }

    @Test
    @DisplayName("GET /shipments/track-status debe devolver el estado del monitoreo en JSON")
    void shouldReturnTrackingStatus() throws Exception {
        TrackingStatus status = TrackingStatus.builder()
                .running(true)
                .total(80)
                .processed(37)
                .updated(35)
                .failed(2)
                .alertsSent(1)
                .currentTrackingNumber("TESTBL000038")
                .startedAt(LocalDateTime.now())
                .message("Monitoreando...")
                .details(List.of())
                .build();

        when(trackingSchedulerService.getTrackingStatus()).thenReturn(status);

        mockMvc.perform(get("/shipments/track-status").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.running").value(true))
                .andExpect(jsonPath("$.total").value(80))
                .andExpect(jsonPath("$.processed").value(37))
                .andExpect(jsonPath("$.updated").value(35))
                .andExpect(jsonPath("$.failed").value(2))
                .andExpect(jsonPath("$.alertsSent").value(1))
                .andExpect(jsonPath("$.currentTrackingNumber").value("TESTBL000038"));
    }

    @Test
    @DisplayName("GET /shipments/{id}/history debe renderizar el historial del embarque")
    void shouldRenderHistory() throws Exception {
        Shipment shipment = Shipment.builder()
                .id(1L)
                .trackingNumber("TESTBL900001")
                .carrier(Carrier.ONE)
                .build();

        ShipmentEventLog log = ShipmentEventLog.builder()
                .id(10L)
                .shipment(shipment)
                .eventType("ETA_UPDATED")
                .description("ETA actualizado")
                .recordedAt(LocalDateTime.now())
                .build();

        when(shipmentService.getShipmentById(1L)).thenReturn(Optional.of(shipment));
        when(shipmentService.getEventLogs(1L)).thenReturn(List.of(log));

        mockMvc.perform(get("/shipments/1/history"))
                .andExpect(status().isOk())
                .andExpect(view().name("history"))
                .andExpect(model().attributeExists("shipment"))
                .andExpect(model().attributeExists("logs"));
    }
}
