package com.shiparrival.service;

import com.shiparrival.adapter.CarrierTrackingAdapter;
import com.shiparrival.adapter.CarrierTrackingAdapterFactory;
import com.shiparrival.adapter.TrackingResult;
import com.shiparrival.domain.Carrier;
import com.shiparrival.domain.Shipment;
import com.shiparrival.domain.ShipmentStatus;
import com.shiparrival.repository.ShipmentRepository;
import com.shiparrival.service.dto.TrackingExecutionSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrackingSchedulerServiceTest {

    @Mock
    private ShipmentService shipmentService;

    @Mock
    private ShipmentRepository shipmentRepository;

    @Mock
    private CarrierTrackingAdapterFactory adapterFactory;

    @Mock
    private CarrierTrackingAdapter trackingAdapter;

    @Mock
    private TelegramNotificationService telegramService;

    private TrackingSchedulerService schedulerService;

    @BeforeEach
    void setUp() {
        schedulerService = new TrackingSchedulerService(
                shipmentService,
                shipmentRepository,
                adapterFactory,
                telegramService,
                0 // 0 delay en tests para velocidad
        );
    }

    @Test
    @DisplayName("Debe ejecutar ciclo de rastreo, actualizar estados y enviar alerta si arriba en <= 3 días")
    void shouldRunTrackingCycleAndTriggerAlerts() {
        Shipment shipment = Shipment.builder()
                .id(1L)
                .trackingNumber("TAOGR4856400")
                .carrier(Carrier.ONE)
                .status(ShipmentStatus.IN_TRANSIT)
                .active(true)
                .alertSent3Days(false)
                .alertSentArrived(false)
                .build();

        TrackingResult result = TrackingResult.builder()
                .success(true)
                .trackingNumber("TAOGR4856400")
                .vesselName("ONE FANTASTIC")
                .voyageNumber("006E")
                .portOfDischarge("MANZANILLO, MEXICO")
                .estimatedArrival(LocalDateTime.of(2026, 10, 22, 9, 0))
                .detectedStatus(ShipmentStatus.NEAR_ARRIVAL)
                .build();

        Shipment updatedShipment = Shipment.builder()
                .id(1L)
                .trackingNumber("TAOGR4856400")
                .carrier(Carrier.ONE)
                .vesselName("ONE FANTASTIC")
                .voyageNumber("006E")
                .portOfDischarge("MANZANILLO, MEXICO")
                .estimatedArrival(LocalDateTime.of(2026, 10, 22, 9, 0))
                .status(ShipmentStatus.NEAR_ARRIVAL)
                .active(true)
                .alertSent3Days(false)
                .alertSentArrived(false)
                .build();

        when(shipmentService.getActiveShipments()).thenReturn(List.of(shipment));
        when(adapterFactory.getAdapter(Carrier.ONE)).thenReturn(trackingAdapter);
        when(trackingAdapter.track("TAOGR4856400")).thenReturn(result);
        when(shipmentService.updateFromTrackingResult(shipment, result)).thenReturn(updatedShipment);
        when(shipmentService.calculateDaysRemaining(any(), any())).thenReturn(2L);
        when(telegramService.sendNearArrivalAlert(any(), eq(2L))).thenReturn(true);

        TrackingExecutionSummary summary = schedulerService.runTrackingCycle();

        assertThat(summary.getTotalProcessed()).isEqualTo(1);
        assertThat(summary.getUpdatedCount()).isEqualTo(1);
        assertThat(summary.getAlertsSentCount()).isEqualTo(1);
        assertThat(summary.getFailedCount()).isEqualTo(0);

        verify(telegramService, times(1)).sendNearArrivalAlert(updatedShipment, 2L);
        verify(shipmentRepository, times(1)).save(updatedShipment);
        assertThat(updatedShipment.isAlertSent3Days()).isTrue();
    }

    @Test
    @DisplayName("No debe ejecutar ciclos simultáneos si ya hay un rastreo en progreso")
    void shouldNotRunConcurrentlyWhenAlreadyRunning() {
        // Marcamos que ya está corriendo mediante reflexión o estado
        schedulerService.setTrackingInProgress(true);

        TrackingExecutionSummary summary = schedulerService.runTrackingCycle();

        assertThat(summary.getTotalProcessed()).isEqualTo(0);
        assertThat(summary.getErrorMessage()).contains("en progreso");
        verifyNoInteractions(adapterFactory);
    }
}
