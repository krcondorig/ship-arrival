package com.shiparrival.service;

import com.shiparrival.adapter.CarrierTrackingAdapter;
import com.shiparrival.adapter.CarrierTrackingAdapterFactory;
import com.shiparrival.adapter.TrackingResult;
import com.shiparrival.domain.Shipment;
import com.shiparrival.domain.ShipmentStatus;
import com.shiparrival.repository.ShipmentRepository;
import com.shiparrival.service.dto.TrackingExecutionSummary;
import com.shiparrival.service.dto.TrackingStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class TrackingSchedulerService {

    private final ShipmentService shipmentService;
    private final ShipmentRepository shipmentRepository;
    private final CarrierTrackingAdapterFactory adapterFactory;
    private final TelegramNotificationService telegramNotificationService;
    private final TaskExecutor trackingTaskExecutor;
    private final long delayMs;

    private final AtomicBoolean trackingInProgress = new AtomicBoolean(false);
    private TrackingStatus trackingStatus = TrackingStatus.idle();

    @Autowired
    public TrackingSchedulerService(ShipmentService shipmentService,
                                    ShipmentRepository shipmentRepository,
                                    CarrierTrackingAdapterFactory adapterFactory,
                                    TelegramNotificationService telegramNotificationService,
                                    @Qualifier("trackingTaskExecutor") TaskExecutor trackingTaskExecutor,
                                    @Value("${tracking.request.delay-ms:2000}") long delayMs) {
        this.shipmentService = shipmentService;
        this.shipmentRepository = shipmentRepository;
        this.adapterFactory = adapterFactory;
        this.telegramNotificationService = telegramNotificationService;
        this.trackingTaskExecutor = trackingTaskExecutor;
        this.delayMs = delayMs;
    }

    public boolean isTrackingInProgress() {
        return trackingInProgress.get();
    }

    public void setTrackingInProgress(boolean inProgress) {
        trackingInProgress.set(inProgress);
    }

    public synchronized TrackingStatus getTrackingStatus() {
        List<String> details = trackingStatus.getDetails() != null
                ? List.copyOf(trackingStatus.getDetails())
                : List.of();
        return trackingStatus.toBuilder()
                .details(details)
                .build();
    }

    /**
     * Inicia el ciclo en segundo plano y vuelve inmediatamente.
     *
     * @return true si el ciclo fue encolado; false si ya hay uno en progreso o el executor lo rechazó.
     */
    public boolean startTrackingCycleAsync() {
        if (!trackingInProgress.compareAndSet(false, true)) {
            log.warn("Se intentó iniciar un monitoreo asíncrono, pero ya hay uno en progreso.");
            return false;
        }

        markQueued();

        try {
            trackingTaskExecutor.execute(this::runTrackingCycleInBackground);
            return true;
        } catch (RuntimeException ex) {
            log.error("No se pudo encolar el monitoreo asíncrono", ex);
            finishWithError("No se pudo iniciar el monitoreo: " + ex.getMessage(), 0);
            trackingInProgress.set(false);
            return false;
        }
    }

    @Scheduled(cron = "${tracking.schedule.cron:0 0 8 * * *}")
    public void scheduleDailyTracking() {
        log.info("Iniciando monitoreo diario programado de buques...");
        if (!startTrackingCycleAsync()) {
            log.warn("No se pudo iniciar el monitoreo diario: ya hay un ciclo en progreso.");
        }
    }

    public TrackingExecutionSummary runTrackingCycle() {
        if (!trackingInProgress.compareAndSet(false, true)) {
            log.warn("Se intentó iniciar un ciclo de monitoreo, pero ya hay uno en progreso.");
            return TrackingExecutionSummary.builder()
                    .totalProcessed(0)
                    .errorMessage("Ya existe un ciclo de monitoreo en progreso")
                    .build();
        }

        try {
            return doRunTrackingCycle();
        } finally {
            trackingInProgress.set(false);
        }
    }

    private void runTrackingCycleInBackground() {
        long startTime = System.currentTimeMillis();
        try {
            doRunTrackingCycle();
        } catch (Exception ex) {
            log.error("Error inesperado en el ciclo de monitoreo asíncrono", ex);
            finishWithError("Error inesperado en el monitoreo: " + ex.getMessage(),
                    System.currentTimeMillis() - startTime);
        } finally {
            trackingInProgress.set(false);
        }
    }

    private TrackingExecutionSummary doRunTrackingCycle() {
        long startTime = System.currentTimeMillis();
        List<Shipment> activeShipments;

        try {
            activeShipments = shipmentService.getActiveShipments();
        } catch (Exception ex) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Error al obtener embarques activos: {}", ex.getMessage());
            finishWithError("Error al obtener embarques activos: " + ex.getMessage(), duration);
            return TrackingExecutionSummary.builder()
                    .totalProcessed(0)
                    .errorMessage("Error al obtener embarques activos: " + ex.getMessage())
                    .executionDurationMs(duration)
                    .build();
        }

        int total = activeShipments.size();
        int processed = 0;
        int updated = 0;
        int failed = 0;
        int alerts = 0;
        List<String> details = new ArrayList<>();

        startStatus(total);
        log.info("Comenzando ciclo de monitoreo para {} embarques activos", total);

        try {
            for (int i = 0; i < total; i++) {
                Shipment shipment = activeShipments.get(i);
                setCurrentTrackingNumber(shipment.getTrackingNumber());

                try {
                    CarrierTrackingAdapter adapter = adapterFactory.getAdapter(shipment.getCarrier());
                    TrackingResult result = adapter.track(shipment.getTrackingNumber());

                    Shipment updatedShipment = shipmentService.updateFromTrackingResult(shipment, result);

                    if (result.isSuccess()) {
                        updated++;
                        details.add("Guía " + shipment.getTrackingNumber() + ": Actualizado - " + updatedShipment.getStatus());

                        boolean alertTriggered = checkAndSendAlerts(updatedShipment);
                        if (alertTriggered) {
                            alerts++;
                        }
                    } else {
                        failed++;
                        details.add("Guía " + shipment.getTrackingNumber() + ": Error - " + result.getErrorMessage());
                    }
                } catch (Exception ex) {
                    failed++;
                    log.error("Error al procesar rastreo de guía {}: {}", shipment.getTrackingNumber(), ex.getMessage());
                    details.add("Guía " + shipment.getTrackingNumber() + ": Fallo inesperado - " + ex.getMessage());
                }

                processed = i + 1;
                updateProgress(processed, updated, failed, alerts);

                // Pausa de cortesía entre peticiones para evitar bloqueos por rate-limiting
                if (delayMs > 0 && i < total - 1) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Monitoreo interrumpido durante pausa de cortesía");
                        break;
                    }
                }
            }
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            finishStatus(processed, total, updated, failed, alerts, duration, details);
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Ciclo de monitoreo finalizado. Procesados: {}, Actualizados: {}, Fallidos: {}, Alertas enviadas: {} en {} ms",
                processed, updated, failed, alerts, duration);

        return TrackingExecutionSummary.builder()
                .totalProcessed(total)
                .updatedCount(updated)
                .failedCount(failed)
                .alertsSentCount(alerts)
                .executionDurationMs(duration)
                .details(details)
                .build();
    }

    private synchronized void markQueued() {
        trackingStatus = TrackingStatus.builder()
                .running(true)
                .message("Preparando monitoreo...")
                .startedAt(LocalDateTime.now())
                .details(new ArrayList<>())
                .build();
    }

    private synchronized void startStatus(int total) {
        trackingStatus = TrackingStatus.builder()
                .running(true)
                .total(total)
                .message("Monitoreando...")
                .startedAt(LocalDateTime.now())
                .details(new ArrayList<>())
                .build();
    }

    private synchronized void setCurrentTrackingNumber(String trackingNumber) {
        trackingStatus.setCurrentTrackingNumber(trackingNumber);
    }

    private synchronized void updateProgress(int processed, int updated, int failed, int alerts) {
        trackingStatus.setProcessed(processed);
        trackingStatus.setUpdated(updated);
        trackingStatus.setFailed(failed);
        trackingStatus.setAlertsSent(alerts);
    }

    private synchronized void finishStatus(int processed, int total, int updated, int failed, int alerts,
                                           long durationMs, List<String> details) {
        trackingStatus.setRunning(false);
        trackingStatus.setProcessed(processed);
        trackingStatus.setTotal(total);
        trackingStatus.setUpdated(updated);
        trackingStatus.setFailed(failed);
        trackingStatus.setAlertsSent(alerts);
        trackingStatus.setCurrentTrackingNumber(null);
        trackingStatus.setFinishedAt(LocalDateTime.now());
        trackingStatus.setDurationMs(durationMs);
        trackingStatus.setMessage(failed > 0 ? "Monitoreo finalizado con fallos" : "Monitoreo finalizado");
        trackingStatus.setDetails(new ArrayList<>(details));
    }

    private synchronized void finishWithError(String message, long durationMs) {
        trackingStatus.setRunning(false);
        trackingStatus.setCurrentTrackingNumber(null);
        trackingStatus.setFinishedAt(LocalDateTime.now());
        trackingStatus.setDurationMs(durationMs);
        trackingStatus.setMessage(message);
    }

    private boolean checkAndSendAlerts(Shipment shipment) {
        boolean sent = false;

        // Caso 1: Arribo próximo (<= 3 días)
        if (shipment.getStatus() == ShipmentStatus.NEAR_ARRIVAL && !shipment.isAlertSent3Days()) {
            Long days = shipmentService.calculateDaysRemaining(shipment, LocalDate.now());
            long daysRemaining = days != null ? days : 0;
            boolean success = telegramNotificationService.sendNearArrivalAlert(shipment, daysRemaining);
            if (success) {
                shipment.setAlertSent3Days(true);
                shipmentRepository.save(shipment);
                sent = true;
            }
        }

        // Caso 2: Buque arribó a puerto
        if (shipment.getStatus() == ShipmentStatus.ARRIVED && !shipment.isAlertSentArrived()) {
            boolean success = telegramNotificationService.sendArrivedAlert(shipment);
            if (success) {
                shipment.setAlertSentArrived(true);
                shipmentRepository.save(shipment);
                sent = true;
            }
        }

        return sent;
    }
}
