package com.shiparrival.service;

import com.shiparrival.adapter.CarrierTrackingAdapter;
import com.shiparrival.adapter.CarrierTrackingAdapterFactory;
import com.shiparrival.adapter.TrackingResult;
import com.shiparrival.domain.Shipment;
import com.shiparrival.domain.ShipmentStatus;
import com.shiparrival.repository.ShipmentRepository;
import com.shiparrival.service.dto.TrackingExecutionSummary;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
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
    private final long delayMs;

    private final AtomicBoolean trackingInProgress = new AtomicBoolean(false);

    @Autowired
    public TrackingSchedulerService(ShipmentService shipmentService,
                                    ShipmentRepository shipmentRepository,
                                    CarrierTrackingAdapterFactory adapterFactory,
                                    TelegramNotificationService telegramNotificationService,
                                    @Value("${tracking.request.delay-ms:2000}") long delayMs) {
        this.shipmentService = shipmentService;
        this.shipmentRepository = shipmentRepository;
        this.adapterFactory = adapterFactory;
        this.telegramNotificationService = telegramNotificationService;
        this.delayMs = delayMs;
    }

    public boolean isTrackingInProgress() {
        return trackingInProgress.get();
    }

    public void setTrackingInProgress(boolean inProgress) {
        trackingInProgress.set(inProgress);
    }

    @Scheduled(cron = "${tracking.schedule.cron:0 0 8 * * *}")
    public void scheduleDailyTracking() {
        log.info("Iniciando monitoreo diario programado de buques...");
        runTrackingCycle();
    }

    public TrackingExecutionSummary runTrackingCycle() {
        if (!trackingInProgress.compareAndSet(false, true)) {
            log.warn("Se intentó iniciar un ciclo de monitoreo, pero ya hay uno en progreso.");
            return TrackingExecutionSummary.builder()
                    .totalProcessed(0)
                    .errorMessage("Ya existe un ciclo de monitoreo en progreso")
                    .build();
        }

        long startTime = System.currentTimeMillis();
        List<Shipment> activeShipments = shipmentService.getActiveShipments();
        int total = activeShipments.size();
        int updated = 0;
        int failed = 0;
        int alerts = 0;
        List<String> details = new ArrayList<>();

        log.info("Comenzando ciclo de monitoreo para {} embarques activos", total);

        try {
            for (int i = 0; i < total; i++) {
                Shipment shipment = activeShipments.get(i);
                try {
                    CarrierTrackingAdapter adapter = adapterFactory.getAdapter(shipment.getCarrier());
                    TrackingResult result = adapter.track(shipment.getTrackingNumber());

                    Shipment updatedShipment = shipmentService.updateFromTrackingResult(shipment, result);

                    if (result.isSuccess()) {
                        updated++;
                        details.add("Guía " + shipment.getTrackingNumber() + ": Actualizado - " + updatedShipment.getStatus());

                        // Revisar alertas
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
            trackingInProgress.set(false);
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Ciclo de monitoreo finalizado. Procesados: {}, Actualizados: {}, Fallidos: {}, Alertas enviadas: {} en {} ms",
                total, updated, failed, alerts, duration);

        return TrackingExecutionSummary.builder()
                .totalProcessed(total)
                .updatedCount(updated)
                .failedCount(failed)
                .alertsSentCount(alerts)
                .executionDurationMs(duration)
                .details(details)
                .build();
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
