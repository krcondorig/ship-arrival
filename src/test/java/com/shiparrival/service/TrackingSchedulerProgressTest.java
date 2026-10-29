package com.shiparrival.service;

import com.shiparrival.adapter.CarrierTrackingAdapter;
import com.shiparrival.adapter.CarrierTrackingAdapterFactory;
import com.shiparrival.adapter.TrackingResult;
import com.shiparrival.domain.Carrier;
import com.shiparrival.domain.Shipment;
import com.shiparrival.domain.ShipmentStatus;
import com.shiparrival.repository.ShipmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TrackingSchedulerProgressTest {

    private ShipmentService shipmentService;
    private ShipmentRepository shipmentRepository;
    private CarrierTrackingAdapterFactory adapterFactory;
    private CarrierTrackingAdapter trackingAdapter;
    private TelegramNotificationService telegramService;
    private DiscordNotificationService discordService;
    private TrackingProgressService progressService;
    private TrackingSchedulerService schedulerService;

    @BeforeEach
    void setUp() {
        shipmentService = mock(ShipmentService.class);
        shipmentRepository = mock(ShipmentRepository.class);
        adapterFactory = mock(CarrierTrackingAdapterFactory.class);
        trackingAdapter = mock(CarrierTrackingAdapter.class);
        telegramService = mock(TelegramNotificationService.class);
        discordService = mock(DiscordNotificationService.class);
        progressService = mock(TrackingProgressService.class);

        schedulerService = new TrackingSchedulerService(
                shipmentService, shipmentRepository, adapterFactory,
                telegramService, discordService, Runnable::run, 0, "HAPAG_LLOYD");
        schedulerService.setProgressService(progressService);
    }

    private Shipment shipment() {
        return Shipment.builder()
                .id(1L).trackingNumber("TESTBL900001").carrier(Carrier.ONE)
                .status(ShipmentStatus.IN_TRANSIT).active(true)
                .alertSent3Days(false).alertSentArrived(false)
                .build();
    }

    private TrackingResult result() {
        return TrackingResult.builder()
                .success(true).trackingNumber("TESTBL900001")
                .vesselName("ONE FANTASTIC").voyageNumber("006E")
                .portOfDischarge("MANZANILLO, MEXICO")
                .estimatedArrival(LocalDateTime.now().plusDays(10))
                .detectedStatus(ShipmentStatus.IN_TRANSIT)
                .build();
    }

    @Test
    @DisplayName("Publica inicio, avance y fin cuando hay identificador de ejecución")
    void shouldPublishProgressDuringCycle() {
        Shipment shipment = shipment();
        TrackingResult result = result();

        when(shipmentService.getActiveShipments()).thenReturn(List.of(shipment));
        when(adapterFactory.getAdapter(Carrier.ONE)).thenReturn(trackingAdapter);
        when(trackingAdapter.track("TESTBL900001")).thenReturn(result);
        when(shipmentService.updateFromTrackingResult(shipment, result)).thenReturn(shipment);

        schedulerService.setRunContext("run-1", "GITHUB_ACTIONS");
        schedulerService.runTrackingCycle();

        verify(progressService).start("run-1", "GITHUB_ACTIONS");
        verify(progressService).update("run-1", 1, 0, 0, 0);
        verify(progressService).update("run-1", 1, 1, 1, 0);
        verify(progressService).complete("run-1", 1, 1, 1, 0);
    }

    @Test
    @DisplayName("Cuenta como fallida la guía que lanza una excepción")
    void shouldPublishFailureCount() {
        Shipment shipment = shipment();

        when(shipmentService.getActiveShipments()).thenReturn(List.of(shipment));
        when(adapterFactory.getAdapter(Carrier.ONE)).thenReturn(trackingAdapter);
        when(trackingAdapter.track("TESTBL900001")).thenThrow(new RuntimeException("portal caído"));

        schedulerService.setRunContext("run-2", "GITHUB_ACTIONS");
        schedulerService.runTrackingCycle();

        verify(progressService).complete("run-2", 1, 1, 0, 1);
    }

    @Test
    @DisplayName("Sin identificador de ejecución no escribe progreso")
    void shouldNotPublishWithoutRunContext() {
        Shipment shipment = shipment();
        TrackingResult result = result();

        when(shipmentService.getActiveShipments()).thenReturn(List.of(shipment));
        when(adapterFactory.getAdapter(Carrier.ONE)).thenReturn(trackingAdapter);
        when(trackingAdapter.track("TESTBL900001")).thenReturn(result);
        when(shipmentService.updateFromTrackingResult(shipment, result)).thenReturn(shipment);

        schedulerService.runTrackingCycle();

        verifyNoInteractions(progressService);
    }

    @Test
    @DisplayName("Si falla la lectura de embarques, marca la ejecución como fallida")
    void shouldMarkRunFailedWhenCycleCannotStart() {
        when(shipmentService.getActiveShipments()).thenThrow(new RuntimeException("base no disponible"));

        schedulerService.setRunContext("run-3", "GITHUB_ACTIONS");
        schedulerService.runTrackingCycle();

        verify(progressService).fail(org.mockito.ArgumentMatchers.eq("run-3"),
                org.mockito.ArgumentMatchers.contains("base no disponible"));
    }
}
