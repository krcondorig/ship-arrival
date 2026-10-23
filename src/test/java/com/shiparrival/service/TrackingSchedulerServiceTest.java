package com.shiparrival.service;

import com.shiparrival.adapter.CarrierTrackingAdapter;
import com.shiparrival.adapter.CarrierTrackingAdapterFactory;
import com.shiparrival.adapter.TrackingResult;
import com.shiparrival.domain.Carrier;
import com.shiparrival.domain.Shipment;
import com.shiparrival.domain.ShipmentStatus;
import com.shiparrival.repository.ShipmentRepository;
import com.shiparrival.service.dto.TrackingExecutionSummary;
import com.shiparrival.service.dto.TrackingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

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

    @Mock
    private DiscordNotificationService discordService;

    private TrackingSchedulerService schedulerService;

    @BeforeEach
    void setUp() {
        schedulerService = createService(Runnable::run);
    }

    private TrackingSchedulerService createService(TaskExecutor executor) {
        return new TrackingSchedulerService(
                shipmentService,
                shipmentRepository,
                adapterFactory,
                telegramService,
                discordService,
                executor,
                0 // 0 delay en tests para velocidad
        );
    }

    private Shipment shipment(Long id, String trackingNumber) {
        return Shipment.builder()
                .id(id)
                .trackingNumber(trackingNumber)
                .carrier(Carrier.ONE)
                .status(ShipmentStatus.IN_TRANSIT)
                .active(true)
                .alertSent3Days(false)
                .alertSentArrived(false)
                .build();
    }

    private TrackingResult successfulResult(String trackingNumber) {
        return TrackingResult.builder()
                .success(true)
                .trackingNumber(trackingNumber)
                .vesselName("TEST VESSEL")
                .voyageNumber("001E")
                .portOfDischarge("MANZANILLO, MEXICO")
                .estimatedArrival(LocalDateTime.now().plusDays(10))
                .detectedStatus(ShipmentStatus.IN_TRANSIT)
                .build();
    }

    @Test
    @DisplayName("Debe ejecutar ciclo de rastreo, actualizar estados y enviar alerta si arriba en <= 3 días")
    void shouldRunTrackingCycleAndTriggerAlerts() {
        Shipment shipment = Shipment.builder()
                .id(1L)
                .trackingNumber("TESTBL900001")
                .carrier(Carrier.ONE)
                .status(ShipmentStatus.IN_TRANSIT)
                .active(true)
                .alertSent3Days(false)
                .alertSentArrived(false)
                .build();

        TrackingResult result = TrackingResult.builder()
                .success(true)
                .trackingNumber("TESTBL900001")
                .vesselName("ONE FANTASTIC")
                .voyageNumber("006E")
                .portOfDischarge("MANZANILLO, MEXICO")
                .estimatedArrival(LocalDateTime.of(2026, 10, 22, 9, 0))
                .detectedStatus(ShipmentStatus.NEAR_ARRIVAL)
                .build();

        Shipment updatedShipment = Shipment.builder()
                .id(1L)
                .trackingNumber("TESTBL900001")
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
        when(trackingAdapter.track("TESTBL900001")).thenReturn(result);
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

    @Test
    @DisplayName("startTrackingCycleAsync debe iniciar el ciclo en segundo plano y publicar el estado final")
    void shouldStartTrackingCycleAsyncAndPublishFinalStatus() {
        Shipment shipment = shipment(1L, "TESTBL000001");
        TrackingResult result = successfulResult("TESTBL000001");

        when(shipmentService.getActiveShipments()).thenReturn(List.of(shipment));
        when(adapterFactory.getAdapter(Carrier.ONE)).thenReturn(trackingAdapter);
        when(trackingAdapter.track("TESTBL000001")).thenReturn(result);
        when(shipmentService.updateFromTrackingResult(shipment, result)).thenReturn(shipment);

        AtomicReference<Runnable> task = new AtomicReference<>();
        TrackingSchedulerService asyncService = createService(task::set);

        boolean started = asyncService.startTrackingCycleAsync();

        assertThat(started).isTrue();
        assertThat(asyncService.isTrackingInProgress()).isTrue();
        assertThat(asyncService.getTrackingStatus().isRunning()).isTrue();
        assertThat(task.get()).isNotNull();

        task.get().run();

        TrackingStatus status = asyncService.getTrackingStatus();
        assertThat(asyncService.isTrackingInProgress()).isFalse();
        assertThat(status.isRunning()).isFalse();
        assertThat(status.getTotal()).isEqualTo(1);
        assertThat(status.getProcessed()).isEqualTo(1);
        assertThat(status.getUpdated()).isEqualTo(1);
        assertThat(status.getFailed()).isZero();
        assertThat(status.getAlertsSent()).isZero();
        assertThat(status.getCurrentTrackingNumber()).isNull();
        assertThat(status.getFinishedAt()).isNotNull();
        assertThat(status.getDetails()).hasSize(1);
    }

    @Test
    @DisplayName("startTrackingCycleAsync no debe iniciar un segundo ciclo mientras hay uno en progreso")
    void shouldNotStartTrackingCycleAsyncTwice() {
        AtomicInteger submissions = new AtomicInteger();
        AtomicReference<Runnable> task = new AtomicReference<>();
        TrackingSchedulerService asyncService = createService(runnable -> {
            submissions.incrementAndGet();
            task.set(runnable);
        });

        boolean first = asyncService.startTrackingCycleAsync();
        boolean second = asyncService.startTrackingCycleAsync();

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(submissions).hasValue(1);
        assertThat(task.get()).isNotNull();
    }

    @Test
    @DisplayName("El estado debe reflejar el progreso después de procesar cada guía")
    void shouldPublishProgressAfterEachShipment() {
        Shipment first = shipment(1L, "TESTBL000001");
        Shipment second = shipment(2L, "TESTBL000002");
        TrackingResult firstResult = successfulResult("TESTBL000001");
        TrackingResult secondResult = successfulResult("TESTBL000002");

        when(shipmentService.getActiveShipments()).thenReturn(List.of(first, second));
        when(adapterFactory.getAdapter(Carrier.ONE)).thenReturn(trackingAdapter);
        when(trackingAdapter.track("TESTBL000001")).thenReturn(firstResult);
        when(trackingAdapter.track("TESTBL000002")).thenAnswer(invocation -> {
            TrackingStatus progress = schedulerService.getTrackingStatus();
            assertThat(progress.isRunning()).isTrue();
            assertThat(progress.getTotal()).isEqualTo(2);
            assertThat(progress.getProcessed()).isEqualTo(1);
            assertThat(progress.getUpdated()).isEqualTo(1);
            assertThat(progress.getCurrentTrackingNumber()).isEqualTo("TESTBL000002");
            return secondResult;
        });
        when(shipmentService.updateFromTrackingResult(first, firstResult)).thenReturn(first);
        when(shipmentService.updateFromTrackingResult(second, secondResult)).thenReturn(second);

        TrackingExecutionSummary summary = schedulerService.runTrackingCycle();

        assertThat(summary.getTotalProcessed()).isEqualTo(2);
        TrackingStatus status = schedulerService.getTrackingStatus();
        assertThat(status.isRunning()).isFalse();
        assertThat(status.getTotal()).isEqualTo(2);
        assertThat(status.getProcessed()).isEqualTo(2);
        assertThat(status.getUpdated()).isEqualTo(2);
        assertThat(status.getFailed()).isZero();
        assertThat(status.getCurrentTrackingNumber()).isNull();
        assertThat(status.getDetails()).hasSize(2);
    }

    @Test
    @DisplayName("Debe procesar un lote de 100 guías y publicar el progreso final")
    void shouldProcessOneHundredShipments() {
        List<Shipment> shipments = IntStream.rangeClosed(1, 100)
                .mapToObj(i -> shipment((long) i, String.format("TESTBL%06d", i)))
                .toList();

        when(shipmentService.getActiveShipments()).thenReturn(shipments);
        when(adapterFactory.getAdapter(Carrier.ONE)).thenReturn(trackingAdapter);
        shipments.forEach(shipment -> {
            TrackingResult result = successfulResult(shipment.getTrackingNumber());
            when(trackingAdapter.track(shipment.getTrackingNumber())).thenReturn(result);
            when(shipmentService.updateFromTrackingResult(shipment, result)).thenReturn(shipment);
        });

        TrackingExecutionSummary summary = schedulerService.runTrackingCycle();

        assertThat(summary.getTotalProcessed()).isEqualTo(100);
        assertThat(summary.getUpdatedCount()).isEqualTo(100);
        TrackingStatus status = schedulerService.getTrackingStatus();
        assertThat(status.getTotal()).isEqualTo(100);
        assertThat(status.getProcessed()).isEqualTo(100);
        assertThat(status.getUpdated()).isEqualTo(100);
        assertThat(status.isRunning()).isFalse();
    }
}
